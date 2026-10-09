package com.vertin.chongleme.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
