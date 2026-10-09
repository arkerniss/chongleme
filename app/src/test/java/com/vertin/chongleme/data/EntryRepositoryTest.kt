package com.vertin.chongleme.data

import com.vertin.chongleme.data.db.EntriesSchema
import com.vertin.chongleme.data.db.EntryDao
import com.vertin.chongleme.data.db.SqlDb
import com.vertin.chongleme.data.db.assertForeignKeysEnabled
import com.vertin.chongleme.data.db.countRows
import com.vertin.chongleme.data.db.openTestDb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EntryRepository] 的契约测试：T3 只认 `StateFlow<List<Entry>>` 与这几个挂起函数，
 * 所以这里逐条钉死「调用完之后快照里能看到什么」。
 *
 * 用真 SQLite（JDBC 内存库）而不是 fake：仓库的价值有一半在于
 * 「写入 → 重读 → 发流」这条链，fake 掉数据库就只剩一个转发器在自证。
 *
 * 时间用注入的 [clock] 固定，`occurredAt` 一律由测试显式给——
 * 仓库层绝不允许拿「现在」冒充「事情发生的时间」。
 */
class EntryRepositoryTest {

    private lateinit var db: SqlDb
    private lateinit var entryDao: EntryDao
    private lateinit var repository: EntryRepository
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        db = openTestDb()
        db.assertForeignKeysEnabled()
        entryDao = EntryDao(db)
        now = 1_000_000L
        // 刻意不传 writeDispatcher：走出厂默认（Dispatchers.IO.limitedParallelism(1)），
        // 并发用例要验证的正是这个默认实现真的把写入串起来了。
        repository = EntryRepository(db, clock = { now })
    }

    @Test
    fun `新仓库的快照是空列表`() {
        assertEquals(emptyList<Entry>(), repository.snapshot())
        assertEquals(emptyList<Entry>(), repository.entries.value)
    }

    @Test
    fun `add 之后快照里立刻能看到记录与其配图`() = runBlocking {
        val id = repository.add(
            occurredAt = 5_000L,
            intensity = 4,
            durationMin = 25,
            mood = Mood.Happy,
            note = "带着两张图",
            photos = listOf(PhotoDraft("a.jpg", 800, 600), PhotoDraft("b.jpg", 400, 300)),
        )

        val entry = repository.snapshot().single()
        assertEquals(id, entry.id)
        assertEquals(5_000L, entry.occurredAt)
        assertEquals(25, entry.durationMin)
        assertEquals(4, entry.intensity)
        assertEquals(Mood.Happy, entry.mood)
        assertEquals("带着两张图", entry.note)
        assertEquals(listOf("a.jpg", "b.jpg"), entry.photos.map { it.relPath })
        assertEquals(listOf(0, 1), entry.photos.map { it.sortOrder })
        assertEquals(800, entry.photos.first().width)
        assertEquals(listOf(entry), repository.entries.value)
    }

    @Test
    fun `occurredAt 由调用方决定而时间戳用注入的时钟`() = runBlocking {
        val id = repository.add(occurredAt = 123_456L, intensity = 3)
        val entry = requireNotNull(repository.snapshot().singleOrNull())
        assertEquals(id, entry.id)
        assertEquals("补记过去的日子：不能拿 now 覆盖", 123_456L, entry.occurredAt)
        assertEquals(1_000_000L, entry.createdAt)
        assertEquals(1_000_000L, entry.updatedAt)
    }

    @Test
    fun `refresh 能读到别处写进来的数据`() = runBlocking {
        entryDao.insert(
            occurredAt = 7_000L,
            durationMin = null,
            intensity = 2,
            mood = Mood.Tired,
            note = "绕过仓库直接写库",
            createdAt = 1L,
        )
        assertTrue("还没刷新时快照里不该有它", repository.snapshot().isEmpty())

        repository.refresh()

        assertEquals("绕过仓库直接写库", repository.snapshot().single().note)
    }

    @Test
    fun `update 改字段并刷新 updated_at 但不改 created_at`() = runBlocking {
        val id = repository.add(occurredAt = 5_000L, intensity = 2, note = "旧")
        now = 2_000_000L

        assertTrue(repository.update(id, occurredAt = 6_000L, intensity = 5, durationMin = 30, mood = Mood.Excited, note = "新"))

        val entry = repository.snapshot().single()
        assertEquals(6_000L, entry.occurredAt)
        assertEquals(5, entry.intensity)
        assertEquals(30, entry.durationMin)
        assertEquals(Mood.Excited, entry.mood)
        assertEquals("新", entry.note)
        assertEquals(1_000_000L, entry.createdAt)
        assertEquals(2_000_000L, entry.updatedAt)
    }

    @Test
    fun `update 不存在的 id 返回 false 且快照不变`() = runBlocking {
        repository.add(occurredAt = 5_000L, intensity = 3, note = "唯一一条")
        val before = repository.snapshot()

        assertFalse(repository.update(9_999L, occurredAt = 1L, intensity = 1, note = "幽灵"))

        assertEquals(before, repository.snapshot())
    }

    @Test
    fun `delete 之后快照清空且配图行真的没了`() = runBlocking {
        val id = repository.add(
            occurredAt = 5_000L,
            intensity = 3,
            photos = listOf(PhotoDraft("a.jpg", 1, 1), PhotoDraft("b.jpg", 1, 1)),
        )
        assertEquals(2L, db.countRows(EntriesSchema.TABLE_PHOTO))

        assertTrue(repository.delete(id))

        assertTrue(repository.snapshot().isEmpty())
        assertEquals("配图必须随记录一起消失", 0L, db.countRows(EntriesSchema.TABLE_PHOTO))
        assertFalse(repository.delete(id))
    }

    @Test
    fun `快照按时间倒序`() = runBlocking {
        repository.add(occurredAt = 2_000L, intensity = 3, note = "中")
        repository.add(occurredAt = 1_000L, intensity = 3, note = "旧")
        repository.add(occurredAt = 3_000L, intensity = 3, note = "新")

        assertEquals(listOf("新", "中", "旧"), repository.snapshot().map { it.note })
    }

    @Test
    fun `补图与删图都会反映到快照`() = runBlocking {
        val id = repository.add(occurredAt = 5_000L, intensity = 3)
        assertEquals(0, repository.snapshot().single().photos.size)

        now = 3_000_000L
        val photoId = repository.addPhoto(id, "later.jpg", 100, 200)
        assertEquals(listOf("later.jpg"), repository.snapshot().single().photos.map { it.relPath })
        assertEquals(3_000_000L, repository.snapshot().single().photos.single().createdAt)

        assertTrue(repository.removePhoto(photoId))
        assertEquals(0, repository.snapshot().single().photos.size)
    }

    @Test
    fun `重排配图后快照里的顺序也变了`() = runBlocking {
        val id = repository.add(
            occurredAt = 5_000L,
            intensity = 3,
            photos = listOf(PhotoDraft("a.jpg", 1, 1), PhotoDraft("b.jpg", 1, 1), PhotoDraft("c.jpg", 1, 1)),
        )
        val ids = repository.snapshot().single().photos.map { it.id }

        assertTrue(repository.reorderPhotos(id, ids.reversed()))

        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), repository.snapshot().single().photos.map { it.relPath })
    }

    /**
     * 写入串行化：50 个并发 `add` 必须一条不丢地落到库里。
     *
     * 这条用例真正防的是 `Dispatchers.IO.limitedParallelism(1)` 被误写成
     * 「每次调用新建一个视图」——那种写法下两个"1 并发视图"互不排斥，
     * 并发写会开始互相覆盖（这里会以条数变少的形式暴露）。
     */
    @Test
    fun `并发写入被串行化且一条都不丢`() = runBlocking {
        val ids = (1..50).map { index ->
            async { repository.add(occurredAt = index.toLong(), intensity = 3, note = "第 $index 条") }
        }.awaitAll()

        assertEquals("rowid 必须互不相同", 50, ids.toSet().size)
        assertEquals(50, repository.snapshot().size)
        assertEquals(50L, db.countRows(EntriesSchema.TABLE_ENTRY))
    }

    @Test
    fun `并发写入不会留下半套配图`() = runBlocking {
        val ids = (1..10).map { index ->
            async {
                repository.add(
                    occurredAt = index.toLong(),
                    intensity = 3,
                    photos = listOf(PhotoDraft("p$index-1.jpg", 1, 1), PhotoDraft("p$index-2.jpg", 1, 1)),
                )
            }
        }.awaitAll()

        assertEquals(10, ids.toSet().size)
        assertEquals(20L, db.countRows(EntriesSchema.TABLE_PHOTO))
        assertTrue(repository.snapshot().all { it.photos.size == 2 })
    }

    @Test
    fun `没有时长的记录不会把平均时长算成 0`() = runBlocking {
        repository.add(occurredAt = 5_000L, intensity = 3)
        assertNull(repository.snapshot().single().durationMin)
    }
}
