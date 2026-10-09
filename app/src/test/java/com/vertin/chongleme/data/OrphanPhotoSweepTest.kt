package com.vertin.chongleme.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 孤儿照片清扫的行为测试。
 *
 * 这段代码的失败模式是「删掉了不该删的东西」，所以测试的重点不是「能删干净」，
 * 而是**在什么情况下必须什么都不做**。每个保护条款都有对应用例。
 *
 * [PhotoStore] 生产上由 `Context.filesDir` 构造，但它也提供了一个直接给目录的
 * internal 构造函数——这里就是用它把根指向 [TemporaryFolder]。
 * 被验证的 `sweep` 与 `PhotoStore` 都是生产代码，没有替身。
 */
class OrphanPhotoSweepTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun storeUnder(dir: File): PhotoStore = PhotoStore(dir)

    private fun photo(id: Long, relPath: String) = Photo(
        id = id,
        entryId = 1L,
        relPath = relPath,
        width = 100,
        height = 100,
        sortOrder = id.toInt(),
        createdAt = 0L,
    )

    private fun entry(id: Long, photos: List<Photo>) = Entry(
        id = id,
        occurredAt = 1_700_000_000_000L,
        durationMin = null,
        intensity = 3,
        mood = null,
        note = "",
        createdAt = 0L,
        updatedAt = 0L,
        photos = photos,
    )

    private fun writePhoto(store: PhotoStore, relPath: String) {
        val file = store.resolve(relPath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `被引用的照片不会被删`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp")
        writePhoto(store, "photos/2026/10/b.webp")

        val removed = OrphanPhotoSweep.sweep(
            entries = listOf(entry(1L, listOf(photo(1L, "photos/2026/10/a.webp")))),
            photoStore = store,
        )

        // b 无引用 → 删掉 1 个；a 必须还在
        assertEquals(1, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
        assertTrue(!store.resolve("photos/2026/10/b.webp").exists())
    }

    @Test
    fun `记录列表为空时一个文件都不删`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp")
        writePhoto(store, "photos/2026/10/b.webp")

        val removed = OrphanPhotoSweep.sweep(entries = emptyList(), photoStore = store)

        assertEquals("空列表必须被视为「读库失败」的同类情况，什么都不做", 0, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
        assertTrue(store.resolve("photos/2026/10/b.webp").isFile)
    }

    @Test
    fun `全部照片都被引用时不做任何删除`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp")

        val removed = OrphanPhotoSweep.sweep(
            entries = listOf(entry(1L, listOf(photo(1L, "photos/2026/10/a.webp")))),
            photoStore = store,
        )

        assertEquals(0, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
    }

    @Test
    fun `磁盘上没有照片时安全返回零`() {
        val store = storeUnder(temp.root)
        val removed = OrphanPhotoSweep.sweep(entries = listOf(entry(1L, emptyList())), photoStore = store)
        assertEquals(0, removed)
    }

    @Test
    fun `多个记录共享同一张照片时按引用集合去重判断`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/shared.webp")

        val removed = OrphanPhotoSweep.sweep(
            entries = listOf(
                entry(1L, listOf(photo(1L, "photos/2026/10/shared.webp"))),
                entry(2L, listOf(photo(2L, "photos/2026/10/shared.webp"))),
            ),
            photoStore = store,
        )

        assertEquals(0, removed)
        assertTrue(store.resolve("photos/2026/10/shared.webp").isFile)
    }

    @Test
    fun `只清扫 photos 目录，不碰同级其它文件`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/orphan.webp")
        // 一个同级的、不属于照片目录的文件（例如数据库文件）
        val other = File(temp.root, "chongleme.db").apply { writeBytes(byteArrayOf(9)) }

        OrphanPhotoSweep.sweep(
            entries = listOf(entry(1L, emptyList())),
            photoStore = store,
        )

        assertTrue("数据库文件绝不能被当成孤儿照片删掉", other.isFile)
    }

    @Test
    fun `清扫是幂等的`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/orphan.webp")
        val entries = listOf(entry(1L, emptyList()))

        val first = OrphanPhotoSweep.sweep(entries, store)
        val second = OrphanPhotoSweep.sweep(entries, store)

        assertEquals(1, first)
        assertEquals("第二次应当无可删", 0, second)
    }
}
