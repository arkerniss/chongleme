package com.vertin.chongleme.data.db

/**
 * 数据库结构的**唯一事实来源**：DDL、列名、版本号都在这里。
 *
 * 为什么把 DDL 抽成字符串常量而不是写在 `onCreate` 里：
 * 单元测试要在没有 Android framework 的 JVM 上跑真 SQLite（见 `EntryDaoTest`），
 * 它必须执行**与生产完全同一份**建表语句。把 DDL 复制到测试里，
 * 测的就不是出厂代码了，而是测试自己的副本——那种测试在 schema 漂移时依然全绿。
 */
object EntriesSchema {

    const val DB_NAME: String = "chongleme.db"
    const val VERSION: Int = 1

    const val TABLE_ENTRY: String = "entry"
    const val TABLE_PHOTO: String = "photo"

    /**
     * 外键约束在 SQLite 里**默认关闭**，而且是 per-connection 的开关，
     * 不是存在库文件里的属性——每建一条连接都要重新开一次。
     * 不开的后果很安静：`ON DELETE CASCADE` 被忽略，删记录后照片行变成永久孤儿。
     */
    const val SQL_FOREIGN_KEYS_ON: String = "PRAGMA foreign_keys=ON"

    /** 建表语句，按依赖顺序执行。 */
    val STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE entry (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          occurred_at INTEGER NOT NULL,
          duration_min INTEGER,
          intensity INTEGER NOT NULL,
          mood TEXT,
          note TEXT,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        )
        """.trimIndent(),

        "CREATE INDEX idx_entry_occurred ON entry(occurred_at DESC)",

        """
        CREATE TABLE photo (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          entry_id INTEGER NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
          rel_path TEXT NOT NULL,
          width INTEGER,
          height INTEGER,
          sort_order INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),

        "CREATE INDEX idx_photo_entry ON photo(entry_id)",
    )

    /**
     * 版本 N 的升级语句。v1 是首个版本，所以现在是空的。
     *
     * 刻意**不写**「版本不认识就 drop 全部重建」的兜底：对私人记录本来说，
     * 升级时静默删库是最不可接受的失败模式，宁可让 [EntriesDb.onUpgrade] 明确地少做事。
     * 将来加 v2 时，在这里补 `2 to listOf(...)`。
     */
    private val MIGRATIONS: Map<Int, List<String>> = emptyMap()

    /** 从 [from] 升到 [to] 需要执行的语句（升到没有登记的版本时返回已登记的部分）。 */
    fun migrations(from: Int, to: Int): List<String> {
        if (to <= from) return emptyList()
        val out = ArrayList<String>()
        for (version in (from + 1)..to) out.addAll(MIGRATIONS[version].orEmpty())
        return out
    }
}
