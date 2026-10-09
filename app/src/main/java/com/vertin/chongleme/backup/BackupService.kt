package com.vertin.chongleme.backup

import android.content.ContentResolver
import android.content.Context
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.PhotoDraft
import com.vertin.chongleme.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
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
 * - 导出**流式写盘**（见 [BackupArchive.writeStreamingToFile]），不在内存里拼整个 ZIP；
 * - 导入**只追加、绝不覆盖**。冲突一律作为新记录插入，重复导入同一份备份
 *   会得到两份记录，但永远不会让已有数据消失——这是刻意的取舍；
 * - 导出的时间戳原样保留（[com.vertin.chongleme.data.Entry.occurredAt]），
 *   补记、跨时区、跨年都不会因为备份/恢复而漂移。
 *
 * 构造函数分成两个：生产走 [Context]，测试直接注入依赖。这样「导出 → 清空 → 导入」
 * 这条唯一退路可以在纯 JVM 单测里跑真实的 SQLite 与真实的文件系统来验证，
 * 而不需要伪造 Android `Context`。
 */
class BackupService internal constructor(
    private val repository: EntryRepository,
    private val photoStore: PhotoStore,
    private val exportDirectoryProvider: () -> File,
    private val contentResolver: ContentResolver? = null,
) {

    constructor(
        context: Context,
        repository: EntryRepository,
        photoStore: PhotoStore,
    ) : this(
        repository = repository,
        photoStore = photoStore,
        exportDirectoryProvider = { File(context.getExternalFilesDir(null), "backup") },
        contentResolver = context.contentResolver,
    )

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

        val manifest = try {
            BackupCodec.encode(entries).toString()
        } catch (e: Throwable) {
            return@withContext ExportResult.Failure(reasonOf("生成清单失败", e))
        }

        // 去重后的配图路径。用 Sequence 而不是先收集成列表：路径本身很轻，
        // 但配合下面的「按需读字节」才能保证任意时刻内存里只有一张图的字节。
        val photoPaths = entries.asSequence()
            .flatMap { it.photos.asSequence() }
            .map { it.relPath }
            .distinct()

        val target = nextExportFile()
        var written = 0
        val failure = BackupArchive.writeStreamingToFile(
            target = target,
            manifestJson = manifest,
            photoPaths = photoPaths.onEach { if (photoStore.read(it) != null) written++ },
            photoBytes = { path -> photoStore.read(path) },
        )
        if (failure != null) {
            return@withContext ExportResult.Failure(failure)
        }

        ExportResult.Success(
            file = target,
            entryCount = entries.size,
            photoCount = written,
            bytes = target.length(),
        )
    }

    /**
     * 导出到调用方给的输出流——例如用户通过系统文件选择器（SAF）挑的位置。
     *
     * 为什么需要这条路径：早先只有「写到 `getExternalFilesDir(null)/backup/` 再弹分享面板」
     * 一种方式，而 Android 11 起 `Android/data/` 下的内容**不再允许第三方应用浏览**，
     * 所以在多数手机上「用文件管理器去那个目录拷走」是走不通的。让用户自己选保存位置
     * 才是真正可用的路径。
     */
    suspend fun exportTo(out: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        val entries = repository.snapshot()
        if (entries.isEmpty()) {
            return@withContext ExportResult.Failure("还没有任何记录，无需备份")
        }
        val manifest = try {
            BackupCodec.encode(entries).toString()
        } catch (e: Throwable) {
            return@withContext ExportResult.Failure(reasonOf("生成清单失败", e))
        }

        val photoPaths = entries.asSequence()
            .flatMap { it.photos.asSequence() }
            .map { it.relPath }
            .distinct()

        var written = 0
        val failure = BackupArchive.writeStreamingTo(
            out = out,
            manifestJson = manifest,
            photoPaths = photoPaths.onEach { if (photoStore.read(it) != null) written++ },
            photoBytes = { path -> photoStore.read(path) },
        )
        if (failure != null) {
            return@withContext ExportResult.Failure(failure)
        }

        ExportResult.Success(
            file = File(""),
            entryCount = entries.size,
            photoCount = written,
            bytes = 0L,
        )
    }

    /** 从一份 ZIP 的字节导入。字节解码与解包都是纯逻辑，便于单测。 */
    suspend fun import(zipBytes: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        val content = try {
            // 走 InputStream 重载：只有它能传解压总量上限（防 zip bomb）
            BackupArchive.read(zipBytes.inputStream(), maxBytes = MAX_IMPORT_DECOMPRESSED_BYTES)
        } catch (e: Throwable) {
            // 捕获 Throwable 而不是 Exception：解开一个几百 MB 的包时抛的是
            // OutOfMemoryError，它继承 Error，用 catch(Exception) 会直接漏到进程外闪退。
            return@withContext ImportResult.Failure(reasonOf("压缩包无法读取", e))
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
            val resolver = contentResolver
                ?: return@withContext Result.failure(IllegalStateException("当前没有可用的 ContentResolver"))
            try {
                val stream = resolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IllegalArgumentException("无法打开所选文件"))
                stream.use { input ->
                    val declared = try {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
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
            } catch (e: Throwable) {
                // 见 import 处的说明：这里必须抓 Throwable，否则 OOM 会绕过 Result 直接闪退。
                Result.failure(if (e is OutOfMemoryError) IllegalStateException(reasonOf("读取失败", e)) else e)
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
        val dir = exportDirectoryProvider()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        return File(dir, "冲了吗-backup-$stamp.zip")
    }

    /** 导出文件所在目录，供 UI 提示用户去哪里找。 */
    fun exportDirectory(): File = exportDirectoryProvider()

    /**
     * 把异常翻译成人能看懂的失败原因。
     *
     * 单独抽出来是因为 `OutOfMemoryError` 的 `message` 是 null，
     * 直接拼 `${e.message}` 会得到「导出失败：null」，对用户毫无信息量。
     */
    private fun reasonOf(prefix: String, e: Throwable): String = when (e) {
        is OutOfMemoryError -> "$prefix：内存不足，备份包可能太大。可以试试少选一些照片分批导出。"
        else -> "$prefix：${e.javaClass.simpleName}${e.message?.let { " · $it" } ?: ""}"
    }

    companion object {
        /**
         * 单次导入读入内存的压缩包上限：64 MB。
         *
         * 这个值是**内存预算**，不是「能支持多大备份」。原来写 500 MB 是错的：
         * 手机堆上限通常 128–512 MB，把上限设得比堆还大等于没有上限，
         * 只会让用户在必然的 OOM 里丢掉全部提示。
         *
         * 64 MB 的压缩包对应大约 100–150 条带满图（每条至多 9 张）的记录。
         * 超过这个量级的备份应该改用 SAF 流式导入，那是后续工作。
         */
        const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024

        /**
         * 解压后总量上限：128 MB。防的是 zip bomb——
         * 一个 1 MB 的压缩包能解出几十 GB，把存储和内存一起写满。
         */
        const val MAX_IMPORT_DECOMPRESSED_BYTES: Long = 128L * 1024 * 1024

        /** 备份包里的格式版本，供将来的兼容分支使用。 */
        fun formatVersionOf(manifest: String): Int? = try {
            JSONObject(manifest).optInt("version", 0).takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }
}
