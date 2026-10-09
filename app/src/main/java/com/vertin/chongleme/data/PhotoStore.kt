package com.vertin.chongleme.data

import android.content.Context
import java.io.File

/**
 * 配图的文件系统一侧。
 *
 * 数据库里存的是 [Photo.relPath]（相对路径），绝对路径在这个类里拼——
 * 这样把应用沙箱换个位置、或者把数据整包搬到另一台机器，库里的路径都仍然有效。
 *
 * 目录按年/月分片（`photos/2026/10/<uuid>.webp`）：单目录塞几千个文件会让
 * 文件管理器和 `File.listFiles` 都变慢，分片是几乎零成本的预防。
 */
class PhotoStore(context: Context) {

    /**
     * 相对路径的基准目录，即 `filesDir`。
     *
     * 注意基准是 `filesDir` 而不是 `filesDir/photos`——因为 [Photo.relPath]
     * 本身已经带了 `photos/` 前缀，若再以 photos 目录为基准就会拼出
     * `photos/photos/...`。
     */
    private val base: File = context.filesDir

    /** 给新照片分配一个相对路径。不创建文件，只决定它该落在哪。 */
    fun allocateRelativePath(year: Int, month: Int, extension: String = "webp"): String =
        "$DIR_PHOTOS/$year/${month.toString().padStart(2, '0')}/${java.util.UUID.randomUUID()}.$extension"

    /** 把相对路径还原成绝对文件。这是全应用唯一允许做这个拼接的地方。 */
    fun resolve(relPath: String): File = File(base, relPath)

    /** 写入字节；返回是否成功。目录不存在会自动创建。 */
    fun write(relPath: String, bytes: ByteArray): Boolean {
        val target = resolve(relPath)
        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 读取字节；文件缺失或不可读时返回 null，而不是抛异常。 */
    fun read(relPath: String): ByteArray? {
        val file = resolve(relPath)
        return try {
            if (file.isFile) file.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    /** 删除一个配图文件。文件本来就不在时返回 true（幂等）。 */
    fun delete(relPath: String): Boolean {
        val file = resolve(relPath)
        return try {
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** 清空所有配图。用于「清空全部数据」。 */
    fun deleteAll(): Boolean = try {
        File(base, DIR_PHOTOS).deleteRecursively()
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DIR_PHOTOS: String = "photos"
    }
}
