package com.vertin.chongleme.data.db

import com.vertin.chongleme.data.PhotoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

/**
 * [PhotoDao] 的内存库测试（真 SQLite / JDBC）。
 *
 * 重点在两条容易被写错的语义：
 * 1. 配图顺序由 `sort_order` 决定，同序号时退化为 id 顺序（顺序必须稳定）；
 * 2. 重排是「全有或全无」——有一个 id 不属于该记录就整批回滚，这也是本项目里
 *    除了显式 `transaction {}` 之外唯一会触发回滚的路径。
 */
class PhotoDaoTest {

    private lateinit var db: SqlDb
    private lateinit var entries: EntryDao
    private lateinit var photos: PhotoDao

    @Before
    fun setUp() {
        db = openTestDb()
        entries = EntryDao(db)
        photos = PhotoDao(db)
    }

    private fun newEntry(note: String = ""): Long =
        entries.insert(occurredAt = 1_000L, durationMin = null, intensity = 3, mood = null, note = note, createdAt = 1L)

    @Test
    fun `插入后按 sort_order 再按 id 排序读回`() {
        val entry = newEntry()
        val third = photos.insert(entry, "c.jpg", 30, 30, sortOrder = 2, createdAt = 1L)
        val first = photos.insert(entry, "a.jpg", 10, 10, sortOrder = 0, createdAt = 1L)
        val second = photos.insert(entry, "b.jpg", 20, 20, sortOrder = 1, createdAt = 1L)

        assertEquals(listOf(first, second, third), photos.byEntry(entry).map { it.id })
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), photos.byEntry(entry).map { it.relPath })
    }

    @Test
    fun `同序号时按 id 稳定排序`() {
        val entry = newEntry()
        val one = photos.insert(entry, "1.jpg", 1, 1, sortOrder = 0, createdAt = 1L)
        val two = photos.insert(entry, "2.jpg", 1, 1, sortOrder = 0, createdAt = 1L)
        val three = photos.insert(entry, "3.jpg", 1, 1, sortOrder = 0, createdAt = 1L)
        assertEquals(listOf(one, two, three), photos.byEntry(entry).map { it.id })
    }

    @Test
    fun `字段原样往返`() {
        val entry = newEntry()
        val id = photos.insert(entry, "2026/03/a.jpg", 800, 600, sortOrder = 4, createdAt = 12_345L)
        val photo = photos.byEntry(entry).single()
        assertEquals(id, photo.id)
        assertEquals(entry, photo.entryId)
        assertEquals("2026/03/a.jpg", photo.relPath)
        assertEquals(800, photo.width)
        assertEquals(600, photo.height)
        assertEquals(4, photo.sortOrder)
        assertEquals(12_345L, photo.createdAt)
    }

    @Test
    fun `insertAll 按顺序给序号且在一个事务里`() {
        val entry = newEntry()
        val drafts = listOf(
            PhotoDraft("a.jpg", 10, 10),
            PhotoDraft("b.jpg", 20, 20),
            PhotoDraft("c.jpg", 30, 30),
        )
        val ids = photos.insertAll(entry, drafts, createdAt = 5L)

        assertEquals(3, ids.size)
        assertEquals(listOf(0, 1, 2), photos.byEntry(entry).map { it.sortOrder })
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), photos.byEntry(entry).map { it.relPath })
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `insertAll 空列表不产生任何行`() {
        val entry = newEntry()
        assertTrue(photos.insertAll(entry, emptyList(), createdAt = 1L).isEmpty())
        assertEquals(0, photos.countForEntry(entry))
    }

    @Test
    fun `byEntries 一次查多条记录并按记录分组`() {
        val first = newEntry("first")
        val second = newEntry("second")
        val third = newEntry("third") // 故意不给它配图
        photos.insertAll(first, listOf(PhotoDraft("f1.jpg", 1, 1), PhotoDraft("f2.jpg", 1, 1)), createdAt = 1L)
        photos.insert(second, "s1.jpg", 1, 1, createdAt = 1L)

        val grouped = photos.byEntries(listOf(first, second, third))
        assertEquals(2, grouped.size)
        assertEquals(listOf("f1.jpg", "f2.jpg"), grouped.getValue(first).map { it.relPath })
        assertEquals(listOf("s1.jpg"), grouped.getValue(second).map { it.relPath })
        assertFalse("没有配图的记录不该出现在 map 里", grouped.containsKey(third))
    }

    @Test
    fun `byEntries 传空集合直接返回空表`() {
        assertTrue(photos.byEntries(emptyList()).isEmpty())
    }

    /** 覆盖「IN (…) 参数上限」的分块逻辑：401 条记录必须一次查全。 */
    @Test
    fun `byEntries 跨过单次绑定上限仍然查全`() {
        val entryIds = db.transaction {
            (0 until 401).map { index ->
                val id = entries.insert(occurredAt = index.toLong(), durationMin = null, intensity = 3, mood = null, note = "", createdAt = 1L)
                photos.insert(id, "p$index.jpg", 1, 1, createdAt = 1L)
                id
            }
        }

        val grouped = photos.byEntries(entryIds)
        assertEquals(401, grouped.size)
        assertEquals("p400.jpg", grouped.getValue(entryIds.last()).single().relPath)
        assertEquals(401L, db.countRows(EntriesSchema.TABLE_PHOTO))
    }

    @Test
    fun `重排成功后顺序真的变了`() {
        val entry = newEntry()
        val ids = photos.insertAll(
            entry,
            listOf(PhotoDraft("a.jpg", 1, 1), PhotoDraft("b.jpg", 1, 1), PhotoDraft("c.jpg", 1, 1)),
            createdAt = 1L,
        )

        assertTrue(photos.reorder(entry, ids.reversed()))

        assertEquals(ids.reversed(), photos.byEntry(entry).map { it.id })
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), photos.byEntry(entry).map { it.relPath })
    }

    @Test
    fun `重排遇到不属于该记录的 id 时整批回滚`() {
        val target = newEntry("target")
        val other = newEntry("other")
        val targetIds = photos.insertAll(
            target,
            listOf(PhotoDraft("a.jpg", 1, 1), PhotoDraft("b.jpg", 1, 1)),
            createdAt = 1L,
        )
        val foreign = photos.insert(other, "foreign.jpg", 1, 1, createdAt = 1L)
        val before = photos.byEntry(target).map { it.id }

        assertFalse("有外部 id，必须返回 false", photos.reorder(target, listOf(targetIds[1], foreign, targetIds[0])))

        assertEquals("整批回滚：顺序必须保持原样", before, photos.byEntry(target).map { it.id })
        assertEquals("另一条记录的图也不能被改到", 0, photos.byEntry(other).single().sortOrder)
    }

    @Test
    fun `重排空列表是无副作用的成功`() {
        val entry = newEntry()
        assertTrue(photos.reorder(entry, emptyList()))
    }

    @Test
    fun `单张删除与整条删除`() {
        val entry = newEntry()
        val ids = photos.insertAll(
            entry,
            listOf(PhotoDraft("a.jpg", 1, 1), PhotoDraft("b.jpg", 1, 1)),
            createdAt = 1L,
        )

        assertTrue(photos.delete(ids[0]))
        assertFalse("重复删除返回 false", photos.delete(ids[0]))
        assertEquals(1, photos.countForEntry(entry))

        assertEquals(1, photos.deleteForEntry(entry))
        assertEquals(0, photos.countForEntry(entry))
        assertEquals(0, photos.deleteForEntry(entry))
    }

    @Test
    fun `给不存在的记录插配图会被外键挡住`() {
        assertThrows(SQLException::class.java) {
            photos.insert(entryId = 404L, relPath = "ghost.jpg", width = 1, height = 1, createdAt = 1L)
        }
        assertEquals(0L, db.countRows(EntriesSchema.TABLE_PHOTO))
    }
}
