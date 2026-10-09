package com.vertin.chongleme.data.db

/**
 * `entry` 表的列下标与全部 SQL 语句。
 *
 * 唯一事实来源：生产 DAO 与单元测试（JDBC 内存 SQLite）执行的是**同一批字符串**。
 * 把 SQL 复制一份到测试里，测的就是测试自己而不是出厂代码，schema 漂移时依然全绿。
 */
internal object EntrySql {

    /** 表名与 DDL 共用同一个常量，避免「DDL 改了、SQL 没改」这种安静的分叉。 */
    const val TABLE: String = EntriesSchema.TABLE_ENTRY

    /**
     * SELECT 的列顺序；[IDX_*] 必须与它严格一一对应。
     * 这份对应关系没有编译期保护，靠 `EntryDaoTest.字段不会串列` 用互不相同的哨兵值守住。
     */
    const val SELECT_COLUMNS: String =
        "id, occurred_at, duration_min, intensity, mood, note, created_at, updated_at"

    const val IDX_ID: Int = 0
    const val IDX_OCCURRED_AT: Int = 1
    const val IDX_DURATION_MIN: Int = 2
    const val IDX_INTENSITY: Int = 3
    const val IDX_MOOD: Int = 4
    const val IDX_NOTE: Int = 5
    const val IDX_CREATED_AT: Int = 6
    const val IDX_UPDATED_AT: Int = 7

    const val SQL_INSERT: String =
        "INSERT INTO $TABLE(occurred_at, duration_min, intensity, mood, note, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"

    /**
     * 编辑只改「这件事本身」与 updated_at；`created_at` 是「这行数据什么时候写的」，
     * 不该因为改了个备注就被刷新——否则它就变成了第二个 updated_at，失去意义。
     */
    const val SQL_UPDATE: String =
        "UPDATE $TABLE SET occurred_at = ?, duration_min = ?, intensity = ?, mood = ?, note = ?, " +
            "updated_at = ? WHERE id = ?"

    const val SQL_SELECT_BY_ID: String = "SELECT $SELECT_COLUMNS FROM $TABLE WHERE id = ?"

    /** 最新在前；同一毫秒内插入的按 id 倒序，保证顺序稳定（列表不能自己洗牌）。 */
    const val SQL_SELECT_ALL: String =
        "SELECT $SELECT_COLUMNS FROM $TABLE ORDER BY occurred_at DESC, id DESC"

    const val SQL_DELETE_BY_ID: String = "DELETE FROM $TABLE WHERE id = ?"

    /** 清空整表。配图行由外键级联删除。 */
    const val SQL_DELETE_ALL: String = "DELETE FROM $TABLE"

    const val SQL_COUNT: String = "SELECT COUNT(*) FROM $TABLE"
}
