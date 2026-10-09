package com.vertin.chongleme.backup

import android.content.Context
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.PhotoDraft
import com.vertin.chongleme.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份的导出与导入。
 *
 * 这是本应用**唯一**的数据搬移方式：没有云同步、没有账号，用户手里这一个 ZIP
 * 就是全部退路。因此这里的实现立场是「宁可慢、宁可保守，也不能丢数据」：
 *
 * - 导出先写内存再落盘，落盘时先写 `.tmp` 再改名，中途被杀不会留下半个备份；
 * - 导入**只追加、绝不覆盖**。冲突一律作为新记录插入，重复导入同一份备份
 *   会得到两份记录，但永远不会让已有数据消失——这是刻意的取舍；
 * - 导出的时间戳原样保留（[com.vertin.chongleme.data.Entry.occurredAt]），
 *   补记、跨时区、跨年都不会因为备份/恢复而漂移。
 */
class BackupService(
    private val context: Context,
    private val repository: EntryRepository,
    private val photoStore: PhotoStore,
) {

    /** 导出结果。 */
    sealed interface ExportResult {
        data class Success(val file: File, val entryCount: Int, val photoCount: Int, val bytes: Long) : ExportResult
        data class Failure(val reason: String) : ExportResult
    }

    /** 导入结果。 */
    sealed interface ImportResult {
        data class Success(
            val entriesAdded: Int,
            val photosAdded: Int,
            /** 备份里登记了、但压缩包里找不到文件或写盘失败的配图数量。 */
            val photosMissing: Int,
            /** 解析阶段就丢弃的配图行数量。 */
            val photosSkipped: Int,
        ) : ImportResult

        data class Failure(val reason: String) : ImportResult
    }

    /**
     * 导出到 `getExternalFilesDir(null)/backup/`。
     *
     * 选外部私有目录的理由：用户能用文件管理器直接拷走，而**不需要任何存储权限**
     * （`Android/data/<包名>/files/` 是所有 Android 版本都允许应用自由读写的位置）。
     */
    suspend fun export(): ExportResult = withContext(Dispatchers.IO) {
        val entries = repository.snapshot()
        if (entries.isEmpty()) {
            return@withContext ExportResult.Failure("还没有任何记录，无需备份")
        }

        val manifest = BackupCodec.encode(entries).toString()

        val photos = LinkedHashMap<String, ByteArray>()
        entries.forEach { entry ->
            entry.photos.forEach { photo ->
                if (photos.containsKey(photo.relPath)) return@forEach
                val bytes = photoStore.read(photo.relPath)
                if (bytes != null) photos[photo.relPath] = bytes
            }
        }

        val zipBytes = try {
            BackupArchive.write(manifest, photos)
        } catch (e: Exception) {
            return@withContext ExportResult.Failure("打包失败：${e.message}")
        }

        val target = nextExportFile()
        if (!BackupArchive.writeToFile(zipBytes, target)) {
            return@withContext ExportResult.Failure("写入文件失败：${target.absolutePath}")
        }

        ExportResult.Success(
            file = target,
            entryCount = entries.size,
            photoCount = photos.size,
            bytes = zipBytes.size.toLong(),
        )
    }

    /** 从一份 ZIP 的字节导入。字节解码与解包都是纯逻辑，便于单测。 */
    suspend fun import(zipBytes: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        val content = try {
            BackupArchive.read(zipBytes)
        } catch (e: Exception) {
            return@withContext ImportResult.Failure("压缩包无法读取：${e.message}")
        }

        val manifest = content.manifest
            ?: return@withContext ImportResult.Failure("这不是「冲了吗」的备份包（缺 ${BackupArchive.ENTRY_MANIFEST}）")

        val decoded = when (val result = BackupCodec.decode(manifest)) {
            is DecodeResult.Failure -> return@withContext ImportResult.Failure(result.reason)
            is DecodeResult.Success -> result
        }

        var entriesAdded = 0
        var photosAdded = 0
        var photosMissing = 0

        decoded.entries.forEach { backupEntry ->
            // 先落配图文件，再写记录：这样记录一入库，它的配图就已经在磁盘上了。
            // 反过来的话列表会先闪一下「没有配图」，而且中途失败会留下指向空文件的记录行。
            val drafts = ArrayList<PhotoDraft>(backupEntry.photos.size)
            backupEntry.photos.sortedBy { it.sortOrder }.forEach { backupPhoto ->
                val bytes = content.photos[backupPhoto.relPath]
                if (bytes == null) {
                    photosMissing++
                    return@forEach
                }
                val newPath = allocateImportedPath(backupPhoto.relPath)
                if (photoStore.write(newPath, bytes)) {
                    drafts += PhotoDraft(
                        relPath = newPath,
                        width = backupPhoto.width,
                        height = backupPhoto.height,
                    )
                    photosAdded++
                } else {
                    photosMissing++
                }
            }

            repository.add(
                occurredAt = backupEntry.occurredAt,
                intensity = backupEntry.intensity,
                durationMin = backupEntry.durationMin,
                mood = backupEntry.mood,
                note = backupEntry.note,
                photos = drafts,
            )
            entriesAdded++
        }

        ImportResult.Success(
            entriesAdded = entriesAdded,
            photosAdded = photosAdded,
            photosMissing = photosMissing,
            photosSkipped = decoded.skippedPhotos,
        )
    }

    /** 从 `content://` 读入全部字节。先做一次尺寸上限检查，避免把超大文件读进内存。 */
    suspend fun readBytes(uri: android.net.Uri, maxBytes: Long = MAX_IMPORT_BYTES): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IllegalArgumentException("无法打开所选文件"))
                stream.use { input ->
                    val declared = try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    } catch (_: Exception) {
                        -1L
                    }
                    if (declared > maxBytes) {
                        return@withContext Result.failure(
                            IllegalArgumentException(
                                "备份包太大（${declared / 1024 / 1024} MB），上限 ${maxBytes / 1024 / 1024} MB"
                            )
                        )
                    }
                    val bytes = input.readBytes()
                    if (bytes.size > maxBytes) {
                        return@withContext Result.failure(
                            IllegalArgumentException(
                                "备份包太大（${bytes.size / 1024 / 1024} MB），上限 ${maxBytes / 1024 / 1024} MB"
                            )
                        )
                    }
                    Result.success(bytes)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 导入进来的配图另起重命名。
     *
     * 不复用备份里的路径是有意的：同名路径可能与现有文件冲突，
     * 覆盖掉一张还在被别的记录引用的图，等于静默损坏既有数据。
     */
    private fun allocateImportedPath(originalRelPath: String): String {
        val name = originalRelPath.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "webp")
        // 沿用原路径里的年/月分片，但在新分片下重新生成文件名
        val parts = originalRelPath.split('/')
        val year = parts.getOrNull(1)?.toIntOrNull()
        val month = parts.getOrNull(2)?.toIntOrNull()
        val now = java.util.Calendar.getInstance()
        return photoStore.allocateRelativePath(
            year = year ?: now.get(java.util.Calendar.YEAR),
            month = month ?: (now.get(java.util.Calendar.MONTH) + 1),
            extension = extension,
        )
    }

    /**
     * 关于 [BackupEntry.createdAt] / [BackupEntry.updatedAt] 的取舍：
     *
     * 这两个字段**不还原**，导入时由仓库层重新盖成「现在」。
     * 理由：它们的语义是「这行数据何时写入本机」，而不是「这件事何时发生」——
     * 后者是 [BackupEntry.occurredAt]，那个字段是原样保留的，所以补记、
     * 跨年、跨时区的记录都不会漂移。至于「本行何时写入」，导入后确实是现在，
     * 强行写回一个属于旧设备的时刻反而会误导排查。
     *
     * 代价是备份包里的这两个字段目前只读不用——保留它们是为了让备份包
     * 自解释（用文本编辑器打开也能看懂一条记录的全貌）。
     */

    private fun nextExportFile(): File {
        val dir = File(context.getExternalFilesDir(null), "backup")
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        return File(dir, "冲了吗-backup-$stamp.zip")
    }

    /** 导出文件所在目录，供 UI 提示用户去哪里找。 */
    fun exportDirectory(): File = File(context.getExternalFilesDir(null), "backup")

    companion object {
        /** 导入包大小上限：500 MB。超出通常意味着选错了文件。 */
        const val MAX_IMPORT_BYTES: Long = 500L * 1024 * 1024

        /** 备份包里的格式版本，供将来的兼容分支使用。 */
        fun formatVersionOf(manifest: String): Int? = try {
            JSONObject(manifest).optInt("version", 0).takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }
}
