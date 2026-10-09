package com.vertin.chongleme.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 出厂数据库入口：一个手写的 `SQLiteOpenHelper`。
 *
 * 为什么不用 Room：Room 的注解处理器走 KSP，而当时最新版 KSP 依赖 kotlin-stdlib 2.3.20，
 * 本项目用的是 Kotlin 2.4.10——这条链上任何一方的版本漂移都会以「编译期玄学报错」的形式
 * 出现，而本项目一共只有两张表、十来条语句。手写 SQL 的维护成本明显更低，
 * 也顺带把「SQL 可以被 JVM 单测直接验证」这件事变成了可能。
 *
 * 关于 WAL：刻意不开。写入已经在仓库层被 `limitedParallelism(1)` 串成单线，
 * 读也只有列表刷新一处，WAL 带来的并发收益为零，却会多出 `-wal` / `-shm` 两个文件
 * （用户手动备份数据库目录时会漏掉它们，恢复出来是残缺的）。
 *
 * 线程：`SQLiteOpenHelper` 自带连接锁，`getWritableDatabase()` 可以从任意线程调用；
 * 但本项目的调用方统一在单线程写调度器上，见 [com.vertin.chongleme.data.EntryRepository]。
 */
class EntriesDb(
    context: Context,
    name: String? = EntriesSchema.DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, name, null, EntriesSchema.VERSION) {

    /**
     * 外键约束是 per-connection 的，必须每条连接开一次。
     * `onConfigure` 是唯一保证「在任何查询之前、且不在事务中」执行的回调——
     * 官方文档明确要求 PRAGMA 只能在这里执行，放到 `onOpen` 会在某些设备上
     * 因为「连接已开始使用」而静默失败，那样 `ON DELETE CASCADE` 就形同虚设。
     */
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL(EntriesSchema.SQL_FOREIGN_KEYS_ON)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in EntriesSchema.STATEMENTS) db.execSQL(statement)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首版，迁移表为空。这里刻意不做 drop-recreate 兜底：静默删库对私人记录本不可接受。
        for (statement in EntriesSchema.migrations(oldVersion, newVersion)) db.execSQL(statement)
    }

    companion object {
        /**
         * 内存库：`SQLiteOpenHelper` 的 name 传 null 时，SQLite 建的就是纯内存数据库，
         * 进程结束即消失。用于 Robolectric 之类的 Android 运行时测试与界面预览。
         */
        fun inMemory(context: Context): EntriesDb = EntriesDb(context, null)
    }
}
