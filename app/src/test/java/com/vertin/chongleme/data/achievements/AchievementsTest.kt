package com.vertin.chongleme.data.achievements

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.support.FixedTimeZoneRule
import com.vertin.chongleme.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * [Achievements] 的规则测试：每枚徽章「什么时候解锁、差多少」都要能被指着说清楚。
 *
 * 默认时区固定 Asia/Shanghai，保证「连续 N 天」这类用例的毫秒值是确定的。
 */
class AchievementsTest {

    @get:Rule
    val timeZone: FixedTimeZoneRule = FixedTimeZoneRule("Asia/Shanghai")

    /** 固定「今天」：徽章评估是纯函数，今天必须由调用方给，测试正好借此把它钉死。 */
    private val today: LocalDate = LocalDate.parse("2026-03-05")

    private fun unlockedIds(entries: List<Entry>, today: LocalDate = this.today): Set<String> =
        Achievements.evaluate(entries, today).filter { it.isUnlocked }.map { it.id }.toSet()

    private fun badge(entries: List<Entry>, id: String, today: LocalDate = this.today): BadgeState =
        Achievements.evaluate(entries, today).first { it.id == id }

    /** 从 [today] 往前连续 [days] 天，每天一条记录。 */
    private fun consecutiveDays(days: Int, today: LocalDate = this.today): List<Entry> =
        (0 until days).map { offset ->
            Fixtures.entry(today.minusDays(offset.toLong()).toString(), durationMin = 30)
        }

    // ── 整体形态 ──────────────────────────────────────────────────────────────

    @Test
    fun `徽章数量与 id 唯一性`() {
        val all = Achievements.evaluate(emptyList(), today)
        assertTrue("任务要求约 12 枚，实际 ${all.size}", all.size >= 12)
        assertEquals("total 与实际枚数必须一致", Achievements.total, all.size)
        assertEquals("id 必须唯一（UI 会拿它当 key）", all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.title.isNotBlank() && it.condition.isNotBlank() })
    }

    @Test
    fun `空数据时全部未解锁且进度从零开始`() {
        val all = Achievements.evaluate(emptyList(), today)
        assertTrue(all.all { !it.isUnlocked })
        assertTrue(Achievements.unlocked(emptyList(), today).isEmpty())

        val first = badge(emptyList(), "first_step") as LockedBadge
        assertEquals(0, first.current)
        assertEquals(1, first.target)
        assertEquals("0/1", first.progressText)
    }

    @Test
    fun `isUnlocked 与具体类型永远一致`() {
        val states = Achievements.evaluate(consecutiveDays(10), today)
        for (state in states) {
            when (state) {
                is UnlockedBadge -> {
                    assertTrue(state.isUnlocked)
                    assertNull("已解锁没有「还差多少」可讲", state.progressText)
                }
                is LockedBadge -> {
                    assertFalse(state.isUnlocked)
                    assertNotNull(state.progressText)
                    assertTrue("进度不能是负的", state.current >= 0)
                    assertTrue("目标必须为正", state.target > 0)
                }
            }
        }
    }

    @Test
    fun `评估顺序稳定不受数据影响`() {
        val order = Achievements.evaluate(emptyList(), today).map { it.id }
        assertEquals(order, Achievements.evaluate(consecutiveDays(40), today).map { it.id })
    }

    // ── 逐枚徽章 ──────────────────────────────────────────────────────────────

    @Test
    fun `第一条记录解锁第一步`() {
        val entries = Fixtures.entriesAt("2026-03-05")
        assertTrue("first_step" in unlockedIds(entries))
        val ten = badge(entries, "count_10") as LockedBadge
        assertEquals("1/10", ten.progressText)
    }

    @Test
    fun `十条记录解锁十次但不解锁五十次`() {
        val ids = unlockedIds(consecutiveDays(10))
        assertTrue("count_10" in ids)
        assertFalse("count_50" in ids)
    }

    @Test
    fun `百条记录解锁百次`() {
        val ids = unlockedIds(consecutiveDays(100))
        assertTrue("count_100" in ids)
    }

    @Test
    fun `连续三天解锁三天不断`() {
        val entries = Fixtures.entriesAt("2026-03-03", "2026-03-04", "2026-03-05")
        val ids = unlockedIds(entries)
        assertTrue("streak_3" in ids)
        assertEquals("3/7", (badge(entries, "streak_7") as LockedBadge).progressText)
    }

    @Test
    fun `今天还没记但昨天记了连长仍然算数`() {
        val entries = Fixtures.entriesAt("2026-03-02", "2026-03-03", "2026-03-04")
        assertTrue("streak_3" in unlockedIds(entries))
    }

    @Test
    fun `断了两天连长与徽章一起退回`() {
        val entries = Fixtures.entriesAt("2026-03-01", "2026-03-02", "2026-03-03")
        val ids = unlockedIds(entries)
        assertFalse("3/3 是前天，连长应该已经断了", "streak_3" in ids)
        assertTrue("但历史最长连长还在", "first_step" in ids)
    }

    @Test
    fun `十四天解锁历史纪录但不解锁满月`() {
        val ids = unlockedIds(consecutiveDays(14))
        assertTrue("streak_7" in ids)
        assertTrue("longest_14" in ids)
        assertFalse("streak_30" in ids)
    }

    @Test
    fun `三十天解锁满月坚持`() {
        assertTrue("streak_30" in unlockedIds(consecutiveDays(30)))
    }

    @Test
    fun `本周三次按 ISO 周统计`() {
        // 2026-03-05 是周四，本周从 3/2（周一）起；2/28 与 3/1 都属于上一周
        val entries = Fixtures.entriesAt("2026-02-28", "2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04")
        val ids = unlockedIds(entries)
        assertTrue("week_3" in ids)
        assertFalse("本月只有 3 条，不该解锁本月十次", "month_10" in ids)
    }

    @Test
    fun `本月十次按自然月统计`() {
        val ten = (1..10).map { Fixtures.entry("2026-03-%02d".format(it), durationMin = 5) }
        val ids = unlockedIds(ten, today = LocalDate.parse("2026-03-15"))
        assertTrue("month_10" in ids)
        assertFalse("3/15 那周只有 3/9、3/10 两条", "week_3" in ids)
    }

    @Test
    fun `单次六十分钟解锁一小时`() {
        val ids = unlockedIds(listOf(Fixtures.entry("2026-03-05", durationMin = 60)))
        assertTrue("session_60" in ids)
        assertFalse("累计只有 60 分钟", "total_600" in ids)
    }

    @Test
    fun `累计十小时解锁十小时`() {
        val entries = (0 until 10).map {
            Fixtures.entry("2026-03-05", durationMin = 60)
        }
        assertTrue("total_600" in unlockedIds(entries))
    }

    @Test
    fun `五种心情都记过才解锁情绪全谱`() {
        val four = Mood.entries.take(4).map { Fixtures.entry("2026-03-05", mood = it) }
        assertFalse("mood_all" in unlockedIds(four))

        val five = Mood.entries.map { Fixtures.entry("2026-03-05", mood = it) }
        assertTrue("mood_all" in unlockedIds(five))
    }

    @Test
    fun `配一张图解锁有图有真相`() {
        val photo = Photo(id = 1L, entryId = 1L, relPath = "2026/03/a.jpg", width = 800, height = 600, sortOrder = 0, createdAt = 0L)
        val withPhoto = listOf(Fixtures.entry("2026-03-05").copy(id = 1L, photos = listOf(photo)))
        assertTrue("photo_first" in unlockedIds(withPhoto))
        assertFalse("photo_first" in unlockedIds(listOf(Fixtures.entry("2026-03-05"))))
    }

    // ── 全解锁 / 退回 / 纯度 / 单调性 ─────────────────────────────────────────

    @Test
    fun `全部条件满足时十四枚全部解锁`() {
        // 120 天连续、每天 60 分钟、五种心情循环、其中一条配图，今天 = 2026-03-15（周日）
        val today = LocalDate.parse("2026-03-15")
        val entries = (0 until 120).map { offset ->
            Fixtures.entry(
                today.minusDays(offset.toLong()).toString(),
                durationMin = 60,
                mood = Mood.entries[offset % Mood.entries.size],
            )
        }.toMutableList()
        entries[0] = entries[0].copy(
            photos = listOf(
                Photo(id = 1L, entryId = 1L, relPath = "2026/03/a.jpg", width = 800, height = 600, sortOrder = 0, createdAt = 0L),
            ),
        )

        val all = Achievements.evaluate(entries, today)
        val locked = all.filter { !it.isUnlocked }.map { it.id }
        assertTrue("还有未解锁的：$locked", locked.isEmpty())
        assertEquals(Achievements.total, Achievements.unlocked(entries, today).size)
    }

    @Test
    fun `删掉数据后徽章诚实地退回`() {
        // 不持久化解锁状态的直接结果：数据没了，徽章也没了。这是刻意的设计立场。
        val rich = consecutiveDays(30)
        assertTrue("连续 30 天应该解锁一批徽章", unlockedIds(rich).size >= 3)
        assertTrue("数据清空后不能还剩徽章", unlockedIds(emptyList()).isEmpty())
    }

    @Test
    fun `更多数据不会让已解锁的徽章变回`() {
        val days = (0 until 40).map { LocalDate.parse("2026-03-05").minusDays(it.toLong()) }.sorted()
        var previous = emptySet<String>()
        for (count in 1..days.size) {
            val entries = days.take(count).map {
                Fixtures.entry(it.toString(), durationMin = 60, mood = Mood.Calm)
            }
            val current = unlockedIds(entries, today = LocalDate.parse("2026-03-05"))
            assertTrue("加入第 $count 天后有徽章退回：${previous - current}", current.containsAll(previous))
            previous = current
        }
    }

    @Test
    fun `评估是纯函数`() {
        val entries = consecutiveDays(10)
        val snapshot = entries.toList()
        assertEquals(Achievements.evaluate(entries, today), Achievements.evaluate(entries, today))
        assertEquals(snapshot, entries)
    }
}
