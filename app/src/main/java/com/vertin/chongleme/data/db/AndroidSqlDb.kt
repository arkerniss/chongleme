package com.vertin.chongleme.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

/**
 * [SqlDb] 的出厂实现：把调用转成 `SQLiteDatabase`。
 *
 * 这个类刻意做到「没有分支、没有 SQL、没有业务判断」——它只是胶水，
 * 因为纯 JVM 单测跑不了它（mockable android.jar 里的 SQLite 是空壳），
 * 所以值得验证的逻辑全都留在 [EntrySql] / [PhotoSql] / [EntryDao] / [PhotoDao] / [EntriesSchema]。
 *
 * 两种构造方式：
 * - [AndroidSqlDb]`(SQLiteDatabase)`：已经拿到库的场景（迁移、集成测试）；
 * - [AndroidSqlDb]`(SQLiteOpenHelper)`：**惰性**打开，构造时不会碰磁盘，
 *   这样在 `Application.onCreate` 里建仓库不会把「打开数据库」做进启动关键路径。
 */
class AndroidSqlDb private constructor(private val open: () -> SQLiteDatabase) : SqlDb {

    constructor(database: SQLiteDatabase) : this({ database })

    constructor(helper: SQLiteOpenHelper) : this({ helper.writableDatabase })

    private val db: SQLiteDatabase get() = open()

    override fun exec(sql: String, args: List<Any?>) {
        db.execSQL(sql, args.toBindArgs())
    }

    override fun execute(sql: String, args: List<Any?>): Int {
        val statement = db.compileStatement(sql)
        return statement.use { bound ->
            bound.bindAll(args)
            bound.executeUpdateDelete()
        }
    }

    override fun executeInsert(sql: String, args: List<Any?>): Long {
        val statement = db.compileStatement(sql)
        return statement.use { bound ->
            bound.bindAll(args)
            bound.executeInsert()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toBindArgs()).use { cursor ->
            val row = CursorRow(cursor)
            val out = ArrayList<T>()
            while (cursor.moveToNext()) out.add(map(row))
            out
        }

    override fun queryScalarLong(sql: String, args: List<Any?>): Long? =
        db.rawQuery(sql, args.toBindArgs()).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    override fun <T> transaction(block: () -> T): T {
        val database = db
        database.beginTransaction()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            // endTransaction 才真正提交或回滚：块内抛异常时走不到 setTransactionSuccessful，
            // 于是这里回滚，异常继续向上抛。
            database.endTransaction()
        }
    }

    private class CursorRow(private val cursor: Cursor) : SqlRow {
        override fun isNull(index: Int): Boolean = cursor.isNull(index)
        override fun getLong(index: Int): Long = cursor.getLong(index)
        override fun getInt(index: Int): Int = cursor.getInt(index)
        override fun getString(index: Int): String? =
            if (cursor.isNull(index)) null else cursor.getString(index)
    }
}

/** `SQLiteStatement` 的下标从 1 开始，`args` 从 0 开始，这里是唯一的换算点。 */
private fun SQLiteStatement.bindAll(args: List<Any?>) {
    clearBindings()
    for (i in args.indices) {
        val index = i + 1
        when (val value = args[i]) {
            null -> bindNull(index)
            is String -> bindString(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Short -> bindLong(index, value.toLong())
            is Byte -> bindLong(index, value.toLong())
            is Double -> bindDouble(index, value)
            is Float -> bindDouble(index, value.toDouble())
            is Boolean -> bindLong(index, if (value) 1L else 0L)
            is ByteArray -> bindBlob(index, value)
            else -> bindString(index, value.toString())
        }
    }
}

/**
 * `rawQuery` / `execSQL` 的绑定参数只能是字符串；SQLite 会按**列亲和性**把文本转回数值，
 * 所以 `Long` / `Int` 传 `"123"` 依然能正确比较 INTEGER 列。
 * 走 `SQLiteStatement` 的语句则保留真实类型（见 [bindAll]），不受此限。
 */
private fun List<Any?>.toBindArgs(): Array<String?> = Array(size) { i -> this[i]?.toString() }
