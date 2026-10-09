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
 * 这段代码的失败模式是「删掉了不该删的东西」，而且**真的发生过**：最初版本把
 * 编辑页里已落盘、尚未保存的照片当成孤儿删了（草稿经 `rememberSaveable` 跨进程
 * 死亡恢复后，那批文件确实没有数据库行引用）。所以这里的重点不是「能删干净」，
 * 而是**在什么情况下必须什么都不做**——每条保护都有对应用例，
 * 其中「宽限期内必须一个都不删」是那次事故的回归测试。
 *
 * [PhotoStore] 生产上由 `Context.filesDir` 构造，但它也提供了直接给目录的
 * internal 构造函数——这里用它把根指向 [TemporaryFolder]。被验证的 `sweep`
 * 与 `PhotoStore` 都是生产代码，没有替身。
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

    private fun writePhoto(store: PhotoStore, relPath: String, ageMillis: Long = Long.MAX_VALUE) {
        val file = store.resolve(relPath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        if (ageMillis != Long.MAX_VALUE) {
            // 把修改时间拨回过去，模拟「早就存在的孤儿文件」
            file.setLastModified(System.currentTimeMillis() - ageMillis)
        }
    }

    /** 关掉宽限期，只测「引用关系」这一维。 */
    private fun sweepNoGrace(
        entries: List<Entry>,
        store: PhotoStore,
        protectedPaths: Set<String> = emptySet(),
    ) = OrphanPhotoSweep.sweep(
        entries = entries,
        photoStore = store,
        protectedPaths = protectedPaths,
        graceMillis = 0L,
    )

    // ── 基本对账 ────────────────────────────────────────────────

    @Test
    fun `被引用的照片不会被删`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp", ageMillis = 60_000)
        writePhoto(store, "photos/2026/10/b.webp", ageMillis = 60_000)

        val removed = sweepNoGrace(listOf(entry(1L, listOf(photo(1L, "photos/2026/10/a.webp")))), store)

        assertEquals(1, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
        assertTrue(!store.resolve("photos/2026/10/b.webp").exists())
    }

    @Test
    fun `全部照片都被引用时不做任何删除`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp", ageMillis = 60_000)

        val removed = sweepNoGrace(listOf(entry(1L, listOf(photo(1L, "photos/2026/10/a.webp")))), store)

        assertEquals(0, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
    }

    @Test
    fun `磁盘上没有照片时安全返回零`() {
        val store = storeUnder(temp.root)
        assertEquals(0, sweepNoGrace(listOf(entry(1L, emptyList())), store))
    }

    @Test
    fun `多个记录共享同一张照片时按引用集合去重判断`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/shared.webp", ageMillis = 60_000)

        val removed = sweepNoGrace(
            listOf(
                entry(1L, listOf(photo(1L, "photos/2026/10/shared.webp"))),
                entry(2L, listOf(photo(2L, "photos/2026/10/shared.webp"))),
            ),
            store,
        )

        assertEquals(0, removed)
        assertTrue(store.resolve("photos/2026/10/shared.webp").isFile)
    }

    @Test
    fun `清扫是幂等的`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/orphan.webp", ageMillis = 60_000)
        val entries = listOf(entry(1L, emptyList()))

        assertEquals(1, sweepNoGrace(entries, store))
        assertEquals("第二次应当无可删", 0, sweepNoGrace(entries, store))
    }

    // ── 保护条款 ────────────────────────────────────────────────

    @Test
    fun `记录列表为空时一个文件都不删`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/a.webp", ageMillis = 60_000)
        writePhoto(store, "photos/2026/10/b.webp", ageMillis = 60_000)

        val removed = sweepNoGrace(emptyList(), store)

        assertEquals("空列表必须被视为「读库失败」的同类情况，什么都不做", 0, removed)
        assertTrue(store.resolve("photos/2026/10/a.webp").isFile)
        assertTrue(store.resolve("photos/2026/10/b.webp").isFile)
    }

    /**
     * 回归测试：这正是曾经把用户刚拍的照片删掉的那条路径。
     *
     * 场景——用户在编辑页拍了照（文件已落盘、数据库里还没有行），此时进程被杀，
     * 下次启动时草稿经 `rememberSaveable` 恢复，但文件在数据库看来仍是孤儿。
     * 宽限期必须让它活下来。
     */
    @Test
    fun `宽限期内的文件绝不删，因为那可能是编辑页里尚未保存的照片`() {
        val store = storeUnder(temp.root)
        // 刚刚写入的文件（默认不拨时间 = 就是现在），无数据库引用
        writePhoto(store, "photos/2026/10/just-captured.webp")

        val removed = OrphanPhotoSweep.sweep(
            entries = listOf(entry(1L, emptyList())),
            photoStore = store,
            graceMillis = OrphanPhotoSweep.DEFAULT_GRACE_MILLIS,
        )

        assertEquals("刚写进来的文件必须被保留", 0, removed)
        assertTrue(store.resolve("photos/2026/10/just-captured.webp").isFile)
    }

    @Test
    fun `超过宽限期的孤儿才被清理`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/old-orphan.webp", ageMillis = 60 * 60 * 1000)

        val removed = OrphanPhotoSweep.sweep(
            entries = listOf(entry(1L, emptyList())),
            photoStore = store,
            graceMillis = OrphanPhotoSweep.DEFAULT_GRACE_MILLIS,
        )

        assertEquals(1, removed)
        assertTrue(!store.resolve("photos/2026/10/old-orphan.webp").exists())
    }

    @Test
    fun `显式受保护的路径即使超过宽限期也不删`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/pending.webp", ageMillis = 60 * 60 * 1000)

        val removed = sweepNoGrace(
            entries = listOf(entry(1L, emptyList())),
            store = store,
            protectedPaths = setOf("photos/2026/10/pending.webp"),
        )

        assertEquals("调用方点名的路径必须无条件保留", 0, removed)
        assertTrue(store.resolve("photos/2026/10/pending.webp").isFile)
    }

    @Test
    fun `只清扫 photos 目录，不碰同级其它文件`() {
        val store = storeUnder(temp.root)
        writePhoto(store, "photos/2026/10/orphan.webp", ageMillis = 60_000)
        // 一个同级的、不属于照片目录的文件（例如数据库文件）
        val other = File(temp.root, "chongleme.db").apply { writeBytes(byteArrayOf(9)) }

        sweepNoGrace(listOf(entry(1L, emptyList())), store)

        assertTrue("数据库文件绝不能被当成孤儿照片删掉", other.isFile)
    }
}
