package com.vertin.chongleme.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包（ZIP）的字节层。
 *
 * 结构：
 * ```
 * entries.json          记录与配图的元数据
 * photos/<年>/<月>/xx.webp
 * ```
 *
 * 纯 JVM 实现（`java.util.zip`），因此可以在单测里做真实的
 * 「打包 → 解包」往返，不需要 Android 运行时。
 */
object BackupArchive {

    const val ENTRY_MANIFEST: String = "entries.json"

    /**
     * 流式写到调用方给的输出流（例如用户通过系统文件选择器挑的目标）。
     *
     * 与 [writeStreamingToFile] 同样的内存立场：任意时刻内存里只有一张配图的字节。
     * 不在这里关闭 [out]——它归调用方（通常是 `ContentResolver.openOutputStream`）管。
     */
    fun writeStreamingTo(
        out: OutputStream,
        manifestJson: String,
        photoPaths: Sequence<String>,
        photoBytes: (String) -> ByteArray?,
    ): String? = try {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            photoPaths.forEach { path ->
                val bytes = photoBytes(path) ?: return@forEach
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        null
    } catch (e: Throwable) {
        // 见 writeStreamingToFile：必须抓 Throwable，OutOfMemoryError 是 Error。
        "导出失败：${e.javaClass.simpleName}${e.message?.let { " · $it" } ?: ""}"
    }

    /**
     * 流式写盘：边读边压边写，**不**在内存里拼出整个 ZIP。
     *
     * 存在的理由：早先的 [write] 会先把全部配图字节收进 `Map<String, ByteArray>`，
     * 再拼成一个 `ByteArray`，最后才落盘。一个用了两年的相册（几百张 2048px WebP）
     * 峰值内存可达数百 MB，直接 OOM——而「导出备份」是本应用唯一的数据退路，
     * 它崩掉等于用户没有退路。
     *
     * 失败时删除半成品，不留一个看似成功、实则截断的 ZIP：那种文件比没有更危险，
     * 因为用户会以为备份好了。
     *
     * @param photoBytes 按需提供每张配图的字节；返回 null 表示文件缺失，跳过该张。
     * @return 成功时为 null，失败时为可读原因。
     */
    fun writeStreamingToFile(
        target: File,
        manifestJson: String,
        photoPaths: Sequence<String>,
        photoBytes: (String) -> ByteArray?,
    ): String? = try {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")

        FileOutputStream(temp).use { fileOut ->
            writeStreamingTo(fileOut, manifestJson, photoPaths, photoBytes)?.let { error ->
                temp.delete()
                return error
            }
        }

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return "无法写入目标文件：${target.absolutePath}"
        }
        null
    } catch (e: Throwable) {
        // 包括 OutOfMemoryError：它是 Error 不是 Exception，用 catch(Exception) 会漏掉，
        // 结果是应用直接闪退且用户得不到任何解释。
        File(target.parentFile, "${target.name}.tmp").delete()
        "导出失败：${e.javaClass.simpleName}${e.message?.let { " · $it" } ?: ""}"
    }

    /** 把 manifest 与各配图字节打成一个 ZIP。 */
    fun write(manifestJson: String, photos: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            photos.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 读到的一切。manifest 为 null 表示包里没有 entries.json。 */
    data class Content(val manifest: String?, val photos: Map<String, ByteArray>)

    fun read(bytes: ByteArray): Content = read(bytes.inputStream())

    /**
     * 流式读取。[maxBytes] 是解压总量的上限，防止一个精心构造的 ZIP 解出几十 GB
     * 把手机存储写满（zip bomb）。超出即停止读取并返回已读到的部分。
     */
    fun read(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): Content {
        var manifest: String? = null
        val photos = LinkedHashMap<String, ByteArray>()
        var total = 0L

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val bytes = zip.readEntryBytes(remaining = maxBytes - total)
                total += bytes.size
                when (entry.name) {
                    ENTRY_MANIFEST -> manifest = bytes.toString(Charsets.UTF_8)
                    else -> if (entry.name.startsWith(PHOTO_PREFIX)) {
                        photos[entry.name] = bytes
                    }
                }
                zip.closeEntry()
                if (total >= maxBytes) break
            }
        }
        return Content(manifest, photos)
    }

    /** 把 ZIP 字节写到文件。先写临时文件再改名，避免写到一半进程被杀留下半个备份。 */
    fun writeToFile(bytes: ByteArray, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        temp.renameTo(target)
    } catch (_: Exception) {
        false
    }

    private fun ZipInputStream.readEntryBytes(remaining: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            if (buffer.size() + read > remaining) {
                buffer.write(chunk, 0, (remaining - buffer.size()).toInt().coerceAtLeast(0))
                break
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private const val PHOTO_PREFIX = "photos/"
    private const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
}
