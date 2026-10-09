package com.vertin.chongleme.data.achievements

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.stats.Stats
import java.time.LocalDate

/**
 * 一枚徽章在界面上的两种状态。契约冻结。
 *
 * 为什么是 sealed interface 而不是 `data class Badge(val unlocked: Boolean)`：
 * 「已解锁」与「未解锁」要携带的信息本来就不同——已解锁只需展示，未解锁还要说清楚
 * 「差多少」。用类型来表达这个区别，UI 的 `when` 就能被编译器穷尽检查，
 * 不会出现「未解锁的徽章忘了显示进度」这种漏项。
 *
 * 两个实现都用 `data class`：Compose 靠 equals 跳过重组，且测试断言起来是一行。
 */
sealed interface BadgeState {
    val id: String
    val isUnlocked: Boolean

    /** 徽章名，例如「三天不断」。 */
    val title: String

    /** 条件文案，例如「连续记录 3 天」。 */
    val condition: String

    /** 进度文案，例如 `2/3`；已解锁时为 null（没有「还差多少」可讲）。 */
    val progressText: String?
}

/** 已解锁。 */
data class UnlockedBadge(
    override val id: String,
    override val title: String,
    override val condition: String,
) : BadgeState {
    override val isUnlocked: Boolean get() = true
    override val progressText: String? get() = null
}

/** 未解锁，带「当前/目标」进度。 */
data class LockedBadge(
    override val id: String,
    override val title: String,
    override val condition: String,
    val current: Int,
    val target: Int,
) : BadgeState {
    override val isUnlocked: Boolean get() = false
    override val progressText: String get() = "$current/$target"
}

/**
 * 徽章系统。
 *
 * 全部由 [Stats] 与入参 `entries` 现场推导，**不落库、不存解锁时间、不存「已读」标记**。
 * 理由：这东西的全部价值就是哄自己坚持；一旦持久化，就要处理迁移、时钟回拨、
 * 数据被删后徽章该不该收回等一堆问题，而它换来的只是「解锁动画播过一次」。
 * 不持久化 = 删掉记录，徽章也诚实地退回去，这一点反而符合私人记录本的诚实感。
 *
 * 纯函数：不读系统时钟（`today` 由调用方给），不碰 Android API，不改入参。
 */
object Achievements {

    /** 徽章总数（T3 的「x/12」分母用它，避免各处硬编码）。 */
    val total: Int get() = RULES.size

    /**
     * 评估全部徽章，顺序与 [RULES] 一致（渲染顺序稳定，不会因数据变化而跳动）。
     */
    fun evaluate(entries: List<Entry>, today: LocalDate): List<BadgeState> {
        val ctx = Context(entries, today)
        return RULES.map { rule ->
            val progress = rule.progress(ctx)
            if (progress.current >= progress.target) {
                UnlockedBadge(id = rule.id, title = rule.title, condition = rule.condition)
            } else {
                LockedBadge(
                    id = rule.id,
                    title = rule.title,
                    condition = rule.condition,
                    current = progress.current,
                    target = progress.target,
                )
            }
        }
    }

    /** 便捷视图：只取已解锁的徽章。 */
    fun unlocked(entries: List<Entry>, today: LocalDate): List<BadgeState> =
        evaluate(entries, today).filter { it.isUnlocked }

    /** 一条规则：算出 `当前 / 目标`，够了就算解锁。 */
    private class Rule(
        val id: String,
        val title: String,
        val condition: String,
        val progress: (Context) -> Progress,
    )

    private class Progress(val current: Int, val target: Int)

    /**
     * 规则的输入快照。
     *
     * 这里把 [Stats] 的计算结果缓存下来（`by lazy`）：14 条规则里有 6 条要看连长/频次，
     * 不缓存就会把同一份列表反复排序十几遍。UI 每次刷新只调一次 [evaluate]，
     * 所以这是「一次遍历，多规则复用」，不是提前优化。
     */
    private class Context(val entries: List<Entry>, val today: LocalDate) {
        val frequency by lazy { Stats.frequency(entries, today) }
        val currentStreak by lazy { Stats.streakDays(entries, today) }
        val longestStreak by lazy { Stats.longestStreak(entries) }
        val totalCount: Int get() = entries.size
        val withDuration: List<Entry> get() = entries.filter { it.durationMin != null }
        val totalDurationMin: Int by lazy { entries.sumOf { it.durationMin ?: 0 } }
        val longestSessionMin: Int get() = entries.maxOfOrNull { it.durationMin ?: 0 } ?: 0
        val moodKinds: Int get() = entries.mapNotNull { it.mood }.distinct().size
        val photoCount: Int get() = entries.sumOf { it.photos.size }
    }

    /**
     * 14 条规则（任务要求「约 12 个」）。
     *
     * 配比是刻意的：前四条奖励「开始」与「积累」，中间三条奖励「连续性」，
     * 后面几条奖励「记录质量」（时长、心情、配图）。全是连续性徽章会让断一天的
     * 用户直接放弃——私人记录本不该有惩罚性设计。
     */
    private val RULES: List<Rule> = listOf(
        Rule("first_step", "第一步", "记下第一条记录") { c ->
            Progress(c.totalCount, 1)
        },
        Rule("count_10", "十次", "累计记录 10 条") { c ->
            Progress(c.totalCount, 10)
        },
        Rule("count_50", "五十次", "累计记录 50 条") { c ->
            Progress(c.totalCount, 50)
        },
        Rule("count_100", "百次", "累计记录 100 条") { c ->
            Progress(c.totalCount, 100)
        },
        Rule("streak_3", "三天不断", "连续记录 3 天") { c ->
            Progress(c.currentStreak, 3)
        },
        Rule("streak_7", "一周不落", "连续记录 7 天") { c ->
            Progress(c.currentStreak, 7)
        },
        Rule("streak_30", "满月坚持", "连续记录 30 天") { c ->
            Progress(c.currentStreak, 30)
        },
        Rule("longest_14", "十四天纪录", "历史上连续记录 14 天") { c ->
            Progress(c.longestStreak, 14)
        },
        Rule("week_3", "本周三次", "本周记录 3 次") { c ->
            Progress(c.frequency.thisWeek, 3)
        },
        Rule("month_10", "本月十次", "本月记录 10 次") { c ->
            Progress(c.frequency.thisMonth, 10)
        },
        Rule("session_60", "一小时", "单次时长达到 60 分钟") { c ->
            Progress(c.longestSessionMin, 60)
        },
        Rule("total_600", "十小时", "累计时长达到 600 分钟") { c ->
            Progress(c.totalDurationMin, 600)
        },
        Rule("mood_all", "情绪全谱", "五种心情都记过") { c ->
            Progress(c.moodKinds, Mood.entries.size)
        },
        Rule("photo_first", "有图有真相", "给记录配一张图") { c ->
            Progress(c.photoCount, 1)
        },
    )
}
