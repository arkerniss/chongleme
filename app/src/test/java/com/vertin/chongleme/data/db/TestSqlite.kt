package com.vertin.chongleme.data.db

import org.junit.Assert.assertEquals

/**
 * 测试用内存库：用 [EntriesSchema] 里**出厂那份** PRAGMA 与 DDL 建库。
 *
 * 这里刻意只做两件事（开外键、跑建表语句），与 [EntriesDb.onConfigure] / [EntriesDb.onCreate]
 * 一一对应。任何一边漏掉，`EntryDaoTest` 里的外键用例都会变红——这就是它的价值：
 * 它让「测试库」和「出厂库」的结构无法悄悄分叉。
 */
internal fun openTestDb(): SqlDb {
    val db = JdbcSqlDb.openMemory()
    db.exec(EntriesSchema.SQL_FOREIGN_KEYS_ON)
    for (statement in EntriesSchema.STATEMENTS) db.exec(statement)
    return db
}

/** 断言外键开关真的生效——级联删除的全部前提就是这一行 PRAGMA。 */
internal fun SqlDb.assertForeignKeysEnabled() {
    assertEquals(
        "PRAGMA foreign_keys 没开，ON DELETE CASCADE 会静默失效",
        1L,
        queryScalarLong("PRAGMA foreign_keys"),
    )
}

/** 直接数表里的行数，用来验证「真的删干净了」而不只是「DAO 说删了」。 */
internal fun SqlDb.countRows(table: String): Long =
    queryScalarLong("SELECT COUNT(*) FROM $table") ?: 0L
