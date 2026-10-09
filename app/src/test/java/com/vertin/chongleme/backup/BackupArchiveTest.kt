package com.vertin.chongleme.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZIP 字节层的往返测试。
 *
 * 这里用真实的 `java.util.zip`，不是 mock——备份的失败模式几乎全在字节层：
 * 中文文件名编码、空文件、重复路径、解压炸弹。这些只有跑真实现才测得出来。
 */
class BackupArchiveTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `打包再解包，manifest 与全部配图字节完整`() {
        val manifest = """{"version":1,"entries":[]}"""
        val photos = mapOf(
            "photos/2026/10/a.webp" to byteArrayOf(1, 2, 3, 4, 5),
            "photos/2026/10/b.webp" to byteArrayOf(9, 8, 7),
        )

        val zip = BackupArchive.write(manifest, photos)
        assertTrue("打包结果应当非空", zip.isNotEmpty())

        val content = BackupArchive.read(zip)
        assertEquals(manifest, content.manifest)
        assertEquals(2, content.photos.size)
        assertArrayEquals(photos["photos/2026/10/a.webp"]!!, content.photos["photos/2026/10/a.webp"]!!)
        assertArrayEquals(photos["photos/2026/10/b.webp"]!!, content.photos["photos/2026/10/b.webp"]!!)
    }

    @Test
    fun `中文备注与中文文件名都能原样往返`() {
        val manifest = """{"version":1,"entries":[{"note":"今天很平静，记一笔。"}]}"""
        val zip = BackupArchive.write(manifest, mapOf("photos/2026/10/照片.webp" to bytes("内容")))

        val content = BackupArchive.read(zip)
        assertEquals(manifest, content.manifest)
        assertNotNull("中文路径应能取回", content.photos["photos/2026/10/照片.webp"])
        assertEquals("内容", content.photos["photos/2026/10/照片.webp"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `没有配图时也能正常往返`() {
        val zip = BackupArchive.write("""{"version":1}""", emptyMap())
        val content = BackupArchive.read(zip)
        assertEquals("""{"version":1}""", content.manifest)
        assertTrue(content.photos.isEmpty())
    }

    @Test
    fun `空字节数组作为配图内容也能往返`() {
        val zip = BackupArchive.write("{}", mapOf("photos/2026/10/empty.webp" to ByteArray(0)))
        val content = BackupArchive.read(zip)
        assertEquals(0, content.photos["photos/2026/10/empty.webp"]!!.size)
    }

    @Test
    fun `包内没有 entries_json 时 manifest 为 null`() {
        // 直接构造一个「只有照片、没有 manifest」的包
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("photos/2026/10/a.webp"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        val content = BackupArchive.read(out.toByteArray())
        assertNull("不含 entries.json 的包应当给出 null manifest", content.manifest)
        assertEquals(1, content.photos.size)
    }

    @Test
    fun `损坏的字节不会抛异常，而是返回空内容`() {
        val content = BackupArchive.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull(content.manifest)
        assertTrue(content.photos.isEmpty())
    }

    @Test
    fun `解压总量上限生效，超大包被截断而不是写满磁盘`() {
        // 造 4 MB 的可压缩内容（全零），解压上限设成 64 KB
        val big = ByteArray(4 * 1024 * 1024)
        val zip = BackupArchive.write("{}", mapOf("photos/2026/10/big.webp" to big))

        // 带上限的读取走 InputStream 那个重载（ByteArray 重载用的是默认上限）
        val content = BackupArchive.read(zip.inputStream(), maxBytes = 64L * 1024)
        val read = content.photos["photos/2026/10/big.webp"]
        assertNotNull(read)
        assertTrue(
            "读取量应被上限截断（实际 ${read!!.size} 字节）",
            read.size <= 64 * 1024
        )
    }

    @Test
    fun `writeToFile 落盘后可再次读出`() {
        val dir = java.nio.file.Files.createTempDirectory("chongleme-backup-test").toFile()
        try {
            val target = java.io.File(dir, "sub/冲了吗-backup-2026-10-09_120000.zip")
            val zip = BackupArchive.write("""{"version":1}""", mapOf("photos/a.webp" to byteArrayOf(7)))

            assertTrue("应当写入成功", BackupArchive.writeToFile(zip, target))
            assertTrue(target.isFile)

            val content = BackupArchive.read(target.readBytes())
            assertEquals("""{"version":1}""", content.manifest)
            assertEquals(1, content.photos.size)

            assertTrue(
                "不应留下 .tmp 残留文件",
                !java.io.File(target.parentFile, "${target.name}.tmp").exists()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writeToFile 覆盖已有文件时不残留旧内容`() {
        val dir = java.nio.file.Files.createTempDirectory("chongleme-backup-test").toFile()
        try {
            val target = java.io.File(dir, "backup.zip")
            target.writeBytes(ByteArray(1024) { 9 })

            val zip = BackupArchive.write("""{"version":1}""", emptyMap())
            assertTrue(BackupArchive.writeToFile(zip, target))

            val read = target.readBytes()
            assertArrayEquals(zip, read)
        } finally {
            dir.deleteRecursively()
        }
    }
}
