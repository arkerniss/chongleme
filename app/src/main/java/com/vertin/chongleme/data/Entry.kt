package com.vertin.chongleme.data

/**
 * 一条记录（「冲了吗」的最小事实单元）。
 *
 * 契约冻结：T3 页面按此签名调用，字段顺序与类型都不得改。
 *
 * 两个时间戳的分工：
 * - [occurredAt] 是「这件事发生在什么时候」，由用户选定，可以补记过去的日子；
 * - [createdAt] / [updatedAt] 是「这行数据什么时候写的」，由仓库层写入，用于冲突排查。
 *
 * 统计（[com.vertin.chongleme.data.stats.Stats]）一律只看 [occurredAt]，
 * 所以补记昨天的记录能正确地补进昨天的格子，而不会落到今天。
 */
data class Entry(
    val id: Long,
    val occurredAt: Long,
    val durationMin: Int?,
    val intensity: Int,
    val mood: Mood?,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val photos: List<Photo> = emptyList(),
)

/**
 * 心情标签。
 *
 * 刻意保持五个：太少区分不出情绪，太多会让记录变成负担——这是个私人本子，
 * 用户不想在选择上花时间。名字即持久化键（数据库里存 `name`），
 * 因此**重命名枚举常量等于改数据格式**。
 */
enum class Mood { Calm, Happy, Tired, Stressed, Excited }

/**
 * 强度取值域：1..5。
 *
 * 常量集中在这里，避免「UI 以为上限是 5、统计以为上限是 10」这类漂移。
 * 数据库那一层不设 CHECK 约束（表结构由实现计划冻结），
 * 所以越界值在写入口处被 [coerce] 收敛。
 */
object Intensity {
    const val MIN: Int = 1
    const val MAX: Int = 5

    /** 1..5，供 UI 生成刻度。 */
    val range: IntRange get() = MIN..MAX

    fun isInRange(value: Int): Boolean = value in MIN..MAX

    fun coerce(value: Int): Int = value.coerceIn(MIN, MAX)
}
