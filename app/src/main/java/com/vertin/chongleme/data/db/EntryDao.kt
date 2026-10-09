package com.vertin.chongleme.data.db

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Intensity
import com.vertin.chongleme.data.Mood

/**
 * `entry` 表的手写 SQL DAO。
 *
 * 职责边界：这里只做「参数 ↔ 行」的映射与语句执行，不缓存、不发 Flow、不认识 Android。
 * 上层要的 `StateFlow<List<Entry>>` 由 [com.vertin.chongleme.data.EntryRepository] 负责。
 */
class EntryDao(private val db: SqlDb) {

    /**
     * 插入一条记录，返回新 rowid。
     *
     * [intensity] 在这里被收敛到 `1..5`：这是数据进入 SQL 前的最后一道门，
     * 而表结构（实现计划冻结）里没有 CHECK 约束，所以越界值只能在这里挡。
     */
    fun insert(
        occurredAt: Long,
        durationMin: Int?,
        intensity: Int,
        mood: Mood?,
        note: String,
        createdAt: Long,
        updatedAt: Long = createdAt,
    ): Long {
        requireDuration(durationMin)
        return db.executeInsert(
            EntrySql.SQL_INSERT,
            listOf<Any?>(
                occurredAt,
                durationMin,
                Intensity.coerce(intensity),
                mood?.name,
                note,
                createdAt,
                updatedAt,
            ),
        )
    }

    /**
     * 覆盖式更新「这件事本身」，返回是否真的改到了行。
     *
     * 返回 false 表示 id 不存在（同时被删了、或从未存在）——调用方需要据此决定要不要提示，
     * 而不是把它当成成功。
     */
    fun update(
        id: Long,
        occurredAt: Long,
        durationMin: Int?,
        intensity: Int,
        mood: Mood?,
        note: String,
        updatedAt: Long,
    ): Boolean {
        requireDuration(durationMin)
        val affected = db.execute(
            EntrySql.SQL_UPDATE,
            listOf<Any?>(
                occurredAt,
                durationMin,
                Intensity.coerce(intensity),
                mood?.name,
                note,
                updatedAt,
                id,
            ),
        )
        return affected > 0
    }

    /**
     * 删除一条记录，返回是否删到了行。
     *
     * 配图由 `photo.entry_id` 上的 `ON DELETE CASCADE` 一并清掉——
     * 前提是连接上开了 `PRAGMA foreign_keys=ON`，见 [EntriesDb.onConfigure]。
     * 这里不手动删 photo：两处都删会让人以为级联是坏的，也会掩盖 PRAGMA 没生效的 bug。
     */
    fun delete(id: Long): Boolean = db.execute(EntrySql.SQL_DELETE_BY_ID, listOf<Any?>(id)) > 0

    /**
     * 清空整表，返回删掉的行数。
     *
     * 一条语句而不是循环 [delete]：调用方（设置页的「清空全部数据」）用循环会让
     * 仓库层每次删除后都整表重读，N 条记录退化成 O(N²) 次查询，
     * 几百条记录时界面会静止十几秒。配图行由 `ON DELETE CASCADE` 一并消失。
     */
    fun deleteAll(): Int = db.execute(EntrySql.SQL_DELETE_ALL)

    fun byId(id: Long): Entry? =
        db.query(EntrySql.SQL_SELECT_BY_ID, listOf<Any?>(id)) { mapEntry(it) }.firstOrNull()

    /** 全部记录，最新在前。数据量是「一个人的私人记录」，一次取完比增量分页更简单也更快。 */
    fun all(): List<Entry> = db.query(EntrySql.SQL_SELECT_ALL) { mapEntry(it) }

    fun count(): Int = (db.queryScalarLong(EntrySql.SQL_COUNT) ?: 0L).toInt()

    private fun mapEntry(row: SqlRow): Entry = Entry(
        id = row.getLong(EntrySql.IDX_ID),
        occurredAt = row.getLong(EntrySql.IDX_OCCURRED_AT),
        durationMin = row.intOrNull(EntrySql.IDX_DURATION_MIN),
        intensity = row.getInt(EntrySql.IDX_INTENSITY),
        mood = moodFromKey(row.getString(EntrySql.IDX_MOOD)),
        // note 列可为 NULL，而模型里 note 是非空 String：NULL → 空串。
        note = row.getString(EntrySql.IDX_NOTE).orEmpty(),
        createdAt = row.getLong(EntrySql.IDX_CREATED_AT),
        updatedAt = row.getLong(EntrySql.IDX_UPDATED_AT),
    )

    private fun requireDuration(durationMin: Int?) {
        require(durationMin == null || durationMin >= 0) {
            "durationMin 不能为负数（未知请用 null）：$durationMin"
        }
    }
}

/**
 * 数据库里存枚举的 `name`。读回时大小写不敏感、未知值退化为 null：
 * 用户手改过数据库不应该让列表整条炸掉，备注还在，只是心情显示为空。
 */
internal fun moodFromKey(key: String?): Mood? {
    if (key.isNullOrEmpty()) return null
    return Mood.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
}
