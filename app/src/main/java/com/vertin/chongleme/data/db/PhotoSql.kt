package com.vertin.chongleme.data.db

/**
 * `photo` 表的列下标与全部 SQL 语句。与 [EntrySql] 同样的立场：唯一事实来源。
 */
internal object PhotoSql {

    /** 表名与 DDL 共用同一个常量。 */
    const val TABLE: String = EntriesSchema.TABLE_PHOTO

    /** SELECT 的列顺序；[IDX_*] 必须与它严格一一对应。 */
    const val SELECT_COLUMNS: String =
        "id, entry_id, rel_path, width, height, sort_order, created_at"

    const val IDX_ID: Int = 0
    const val IDX_ENTRY_ID: Int = 1
    const val IDX_REL_PATH: Int = 2
    const val IDX_WIDTH: Int = 3
    const val IDX_HEIGHT: Int = 4
    const val IDX_SORT_ORDER: Int = 5
    const val IDX_CREATED_AT: Int = 6

    const val SQL_INSERT: String =
        "INSERT INTO $TABLE(entry_id, rel_path, width, height, sort_order, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)"

    const val SQL_SELECT_BY_ENTRY: String =
        "SELECT $SELECT_COLUMNS FROM $TABLE WHERE entry_id = ? ORDER BY sort_order ASC, id ASC"

    const val SQL_DELETE_BY_ID: String = "DELETE FROM $TABLE WHERE id = ?"
    const val SQL_DELETE_BY_ENTRY: String = "DELETE FROM $TABLE WHERE entry_id = ?"
    const val SQL_COUNT_BY_ENTRY: String = "SELECT COUNT(*) FROM $TABLE WHERE entry_id = ?"

    /** 重排用：`AND entry_id = ?` 保证一张图不会被挪到别人的记录下面。 */
    const val SQL_UPDATE_SORT_ORDER: String =
        "UPDATE $TABLE SET sort_order = ? WHERE id = ? AND entry_id = ?"

    /**
     * 一次查多条记录的配图。
     *
     * 为什么必须批量而不是「每条记录查一次」：列表页一屏二十条记录，
     * 逐条查就是二十次跨进程/跨 Cursor 的往返，这是最典型的 N+1。
     */
    fun selectByEntries(entryCount: Int): String =
        "SELECT $SELECT_COLUMNS FROM $TABLE WHERE entry_id IN (${placeholders(entryCount)}) " +
            "ORDER BY entry_id ASC, sort_order ASC, id ASC"
}
