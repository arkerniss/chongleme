package com.vertin.chongleme.data.db

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.PhotoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.SQLException

/**
 * [EntryDao] 的内存库测试——**真 SQLite**（sqlite-jdbc 的 `:memory:`），不是 mock。
 *
 * 为什么这件事必须以这种形式做：Android 单元测试里的 `android.jar` 是 mockable 空壳，
 * `SQLiteOpenHelper(ctx, null, ...)` 指向的内存库在纯 JVM 上跑不起来。要让
 * 「增删改查 / 级联删除 / 事务回滚」得到真验证，就得把执行层换成真 SQLite。
 * 建表用的是 [EntriesSchema] 里出厂那份 DDL 与 PRAGMA，DAO 用的是出厂那份代码与 SQL，
 * 因此这里验证的不是测试副本，而是出厂逻辑在真库上的行为。
 *
 * 未覆盖的部分（诚实声明）：[AndroidSqlDb] 这层 JDBC/SQLiteDatabase 适配与
 * [EntriesDb] 的 `onConfigure`/`onCreate` 回调本身——它们只能在 Android 运行时
 * （Robolectric 或真机）里被执行；本文件验证的是它们所依赖的同一批 DDL 与 SQL。
 */
class EntryDaoTest {

    private lateinit var db: SqlDb
    private lateinit var entries: EntryDao
    private lateinit var photos: PhotoDao

    @Before
    fun setUp() {
        db = openTestDb()
        entries = EntryDao(db)
        photos = PhotoDao(db)
    }

    // ── 增删改查 ──────────────────────────────────────────────────────────────

    @Test
    fun `插入后能按 id 原样读回所有字段`() {
        val id = entries.insert(
            occurredAt = 1_700_000_000_000L,
            durationMin = 25,
            intensity = 4,
            mood = Mood.Happy,
            note = "今天不错",
            createdAt = 1_700_000_000_100L,
            updatedAt = 1_700_000_000_200L,
        )

        val loaded = requireNotNull(entries.byId(id))
        assertEquals(
            Entry(
                id = id,
                occurredAt = 1_700_000_000_000L,
                durationMin = 25,
                intensity = 4,
                mood = Mood.Happy,
                note = "今天不错",
                createdAt = 1_700_000_000_100L,
                updatedAt = 1_700_000_000_200L,
            ),
            loaded,
        )
    }

    @Test
    fun `可空字段的 null 原样往返`() {
        val id = entries.insert(
            occurredAt = 1_700_000_000_000L,
            durationMin = null,
            intensity = 3,
            mood = null,
            note = "",
            createdAt = 1L,
        )
        val loaded = requireNotNull(entries.byId(id))
        assertNull("没填时长就是 null，不能变成 0", loaded.durationMin)
        assertNull("没选心情就是 null", loaded.mood)
        assertEquals("", loaded.note)
        assertEquals(1L, loaded.updatedAt)
    }

    @Test
    fun `字段不会串列`() {
        // 每个字段给一个互不相同的哨兵值：任何 IDX_* 常量写错都会在这里被抓到
        val id = entries.insert(
            occurredAt = 1_111L,
            durationMin = 7,
            intensity = 3,
            mood = Mood.Calm,
            note = "note-sentinel",
            createdAt = 2_222L,
            updatedAt = 3_333L,
        )
        val loaded = requireNotNull(entries.byId(id))
        assertEquals(1_111L, loaded.occurredAt)
        assertEquals(7, loaded.durationMin)
        assertEquals(3, loaded.intensity)
        assertEquals(Mood.Calm, loaded.mood)
        assertEquals("note-sentinel", loaded.note)
        assertEquals(2_222L, loaded.createdAt)
        assertEquals(3_333L, loaded.updatedAt)
    }

    @Test
    fun `SQL NULL 的备注读成空串而不是崩掉`() {
        // 模拟「有人手改过数据库」：note 列本身允许 NULL，而模型里是非空 String
        val id = insertRaw(note = null, mood = "Calm")
        assertEquals("", requireNotNull(entries.byId(id)).note)
    }

    @Test
    fun `未知心情字符串退化为 null 但备注还在`() {
        val id = insertRaw(mood = "Weird", note = "手改过")
        val loaded = requireNotNull(entries.byId(id))
        assertNull(loaded.mood)
        assertEquals("手改过", loaded.note)
    }

    @Test
    fun `五种心情都能往返`() {
        val ids = Mood.entries.map { mood ->
            mood to entries.insert(
                occurredAt = 1_700_000_000_000L,
                durationMin = null,
                intensity = 3,
                mood = mood,
                note = mood.name,
                createdAt = 1L,
            )
        }
        for ((mood, id) in ids) {
            assertEquals(mood, requireNotNull(entries.byId(id)).mood)
        }
    }

    @Test
    fun `强度越界会被收敛到 1 到 5`() {
        val tooLow = entries.insert(1L, null, 0, null, "", 1L)
        val tooHigh = entries.insert(1L, null, 99, null, "", 1L)
        assertEquals(1, requireNotNull(entries.byId(tooLow)).intensity)
        assertEquals(5, requireNotNull(entries.byId(tooHigh)).intensity)
    }

    @Test
    fun `负数时长被拒绝`() {
        // insert(occurredAt, durationMin, intensity, mood, note, createdAt)
        assertThrows(IllegalArgumentException::class.java) {
            entries.insert(1L, -1, 3, null, "", 1L)
        }
        val id = entries.insert(1L, 10, 3, null, "", 1L)
        // update(id, occurredAt, durationMin, intensity, mood, note, updatedAt)
        assertThrows(IllegalArgumentException::class.java) {
            entries.update(id, 1L, -5, 3, null, "", 2L)
        }
        // 越界强度是收敛而不是抛异常
        assertTrue(entries.update(id, 1L, 10, 99, null, "", 2L))
        assertEquals(5, requireNotNull(entries.byId(id)).intensity)
    }

    @Test
    fun `rowid 递增且不重复`() {
        val first = entries.insert(1L, null, 1, null, "", 1L)
        val second = entries.insert(2L, null, 1, null, "", 1L)
        val third = entries.insert(3L, null, 1, null, "", 1L)
        assertEquals("三个 rowid 必须互不相同", 3, setOf(first, second, third).size)
        assertTrue("$first, $second, $third 必须递增", first < second && second < third)
    }

    @Test
    fun `全部记录按时间倒序同一时刻按 id 倒序`() {
        val middle = entries.insert(2_000L, null, 1, null, "middle", 1L)
        val oldest = entries.insert(1_000L, null, 1, null, "oldest", 1L)
        val newestFirst = entries.insert(3_000L, null, 1, null, "newest", 1L)
        val newestSecond = entries.insert(3_000L, null, 1, null, "newest-later", 1L)

        assertEquals(
            listOf(newestSecond, newestFirst, middle, oldest),
            entries.all().map { it.id },
        )
    }

    @Test
    fun `更新会改字段与 updated_at 但不改 created_at`() {
        val id = entries.insert(
            occurredAt = 1_000L,
            durationMin = 10,
            intensity = 2,
            mood = Mood.Tired,
            note = "旧",
            createdAt = 5_000L,
            updatedAt = 5_000L,
        )

        assertTrue(entries.update(id, 9_000L, 45, 5, Mood.Excited, "新", updatedAt = 7_777L))

        val loaded = requireNotNull(entries.byId(id))
        assertEquals(9_000L, loaded.occurredAt)
        assertEquals(45, loaded.durationMin)
        assertEquals(5, loaded.intensity)
        assertEquals(Mood.Excited, loaded.mood)
        assertEquals("新", loaded.note)
        assertEquals("created_at 是「这行数据什么时候写的」，不该被编辑刷新", 5_000L, loaded.createdAt)
        assertEquals(7_777L, loaded.updatedAt)
    }

    @Test
    fun `更新不存在的 id 返回 false`() {
        assertFalse(entries.update(12_345L, 1L, null, 3, null, "", 2L))
    }

    @Test
    fun `删除返回是否真的删到了行`() {
        val id = entries.insert(1L, null, 3, null, "", 1L)
        assertEquals(1, entries.count())

        assertTrue(entries.delete(id))
        assertNull(entries.byId(id))
        assertEquals(0, entries.count())
        assertFalse("重复删除必须返回 false，而不是假装成功", entries.delete(id))
    }

    @Test
    fun `空库的 count 与 all`() {
        assertEquals(0, entries.count())
        assertTrue(entries.all().isEmpty())
    }

    // ── 外键与级联 ────────────────────────────────────────────────────────────

    @Test
    fun `外键开关确实生效`() {
        // 没有这一行 PRAGMA，级联删除会被静默忽略——这是最安静也最危险的失败模式
        db.assertForeignKeysEnabled()
    }

    @Test
    fun `给不存在的记录插配图会被外键挡住`() {
        assertThrows(SQLException::class.java) {
            photos.insert(entryId = 999L, relPath = "x.jpg", width = 1, height = 1, createdAt = 1L)
        }
        assertEquals(0L, db.countRows(EntriesSchema.TABLE_PHOTO))
    }

    @Test
    fun `删除记录会级联删掉它的配图`() {
        val keep = entries.insert(1_000L, null, 3, null, "保留", 1L)
        val doomed = entries.insert(2_000L, null, 3, null, "删除", 1L)
        photos.insertAll(keep, listOf(PhotoDraft("keep-1.jpg", 10, 10)), createdAt = 1L)
        photos.insertAll(
            doomed,
            listOf(PhotoDraft("doom-1.jpg", 20, 20), PhotoDraft("doom-2.jpg", 30, 30)),
            createdAt = 1L,
        )
        assertEquals(3L, db.countRows(EntriesSchema.TABLE_PHOTO))

        assertTrue(entries.delete(doomed))

        assertEquals("只应剩下保留记录的那张图", 1L, db.countRows(EntriesSchema.TABLE_PHOTO))
        assertEquals(1, photos.countForEntry(keep))
        assertEquals("photo 表里不能留下孤儿行", 0, photos.countForEntry(doomed))
        assertTrue(photos.byEntries(listOf(doomed)).isEmpty())
    }

    // ── 事务 ──────────────────────────────────────────────────────────────────

    @Test
    fun `事务提交后两条记录都可见`() {
        db.transaction {
            entries.insert(1_000L, null, 3, null, "a", 1L)
            entries.insert(2_000L, null, 3, null, "b", 1L)
        }
        assertEquals(2, entries.count())
    }

    @Test
    fun `事务抛异常时整批回滚`() {
        val boom = IllegalStateException("故意失败")
        val thrown = assertThrows(IllegalStateException::class.java) {
            db.transaction {
                entries.insert(1_000L, null, 3, null, "会被回滚", 1L)
                entries.insert(2_000L, null, 3, null, "也会被回滚", 1L)
                throw boom
            }
        }
        assertEquals(boom, thrown)
        assertEquals("回滚后一条都不该留下", 0, entries.count())
        assertEquals(0L, db.countRows(EntriesSchema.TABLE_ENTRY))
    }

    @Test
    fun `跨两张表的事务同样整体回滚`() {
        assertThrows(IllegalStateException::class.java) {
            db.transaction {
                val id = entries.insert(1_000L, null, 3, null, "带图", 1L)
                photos.insertAll(id, listOf(PhotoDraft("a.jpg", 10, 10)), createdAt = 1L)
                throw IllegalStateException("故意失败")
            }
        }
        assertEquals(0L, db.countRows(EntriesSchema.TABLE_ENTRY))
        assertEquals(0L, db.countRows(EntriesSchema.TABLE_PHOTO))
    }

    @Test
    fun `回滚之后连接仍然可用`() {
        assertThrows(IllegalStateException::class.java) {
            db.transaction {
                entries.insert(1_000L, null, 3, null, "会被回滚", 1L)
                throw IllegalStateException("故意失败")
            }
        }
        // 事务状态必须被恢复：否则下一次写入会撞上「还有一个未结束的事务」
        val id = entries.insert(2_000L, null, 3, null, "回滚之后", 1L)
        assertEquals("回滚之后", requireNotNull(entries.byId(id)).note)
        assertEquals(1, entries.count())
    }

    // ── 测试自己的写入通道（模拟「外部写入者」） ──────────────────────────────

    /**
     * 故意在测试里写一份 INSERT 字面量：用来构造 DAO 正常路径造不出来的行
     * （SQL NULL 的备注、未知的心情字符串），模拟「有人手改过数据库」。
     * 这不是重复出厂 SQL，而是**测试自己的夹具**。
     */
    private fun insertRaw(
        occurredAt: Long = 1_700_000_000_000L,
        durationMin: Int? = null,
        intensity: Int = 3,
        mood: String? = null,
        note: String? = null,
        createdAt: Long = 1L,
        updatedAt: Long = 1L,
    ): Long = db.executeInsert(
        "INSERT INTO entry(occurred_at, duration_min, intensity, mood, note, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
        listOf<Any?>(occurredAt, durationMin, intensity, mood, note, createdAt, updatedAt),
    )
}
