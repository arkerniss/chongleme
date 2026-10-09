package com.vertin.chongleme.data.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * 测试侧的 [SqlDb] 实现：把**出厂那批 SQL 常量**跑在真 SQLite 上（sqlite-jdbc 的内存库）。
 *
 * 为什么不是 Robolectric：Robolectric 会在测试运行期去 Maven Central 下载
 * android-all-instrumented（上百 MB）并写 `~/.m2`，在受限沙箱里不可靠；
 * 而这里换来的验证强度是一样的——同样的 DDL、同样的 DAO 代码、同样的 SQL 字符串，
 * 底下都是真 SQLite（同一个 C 实现的移植版），外键级联与事务回滚都是真行为。
 *
 * 这个类**只做参数绑定与结果集包装**，一句 SQL 都不生成——
 * 生成 SQL 的职责在 DAO/常量里，测的就是出厂那部分。
 *
 * 没有「驱动不存在就跳过」的守卫：sqlite-jdbc 是 testImplementation 的硬依赖，
 * 驱动缺失时这里必须炸掉。静默 skip 的测试会在 CI 上显示绿色，比没有测试更危险。
 */
internal class JdbcSqlDb private constructor(private val connection: Connection) : SqlDb {

    /** 事务嵌套深度。SQLite 不支持真正的嵌套事务，这里用「最外层提交/回滚」模拟。 */
    private var depth: Int = 0

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            connection.createStatement().use { it.execute(sql) }
        } else {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.execute()
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>): Int =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }

    override fun executeInsert(sql: String, args: List<Any?>): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else lastInsertRowId()
            }
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { result ->
                val row = ResultSetRow(result)
                val out = ArrayList<T>()
                while (result.next()) out.add(map(row))
                out
            }
        }

    override fun queryScalarLong(sql: String, args: List<Any?>): Long? =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val value = result.getLong(1)
                if (result.wasNull()) null else value
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) {
            // 嵌套调用复用最外层事务：内层不提交，异常照样往上抛给最外层统一回滚。
            depth++
            return try {
                block()
            } finally {
                depth--
            }
        }

        depth = 1
        connection.autoCommit = false
        return try {
            val result = block()
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            depth = 0
            connection.autoCommit = true
        }
    }

    private fun lastInsertRowId(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { result ->
                if (result.next()) result.getLong(1) else error("无法取回 last_insert_rowid()")
            }
        }

    private class ResultSetRow(private val result: ResultSet) : SqlRow {
        // SqlRow 用 0 基下标（对齐 Cursor），JDBC 用 1 基：这里是唯一的换算点。
        override fun isNull(index: Int): Boolean = result.getObject(index + 1) == null
        override fun getLong(index: Int): Long = result.getLong(index + 1)
        override fun getInt(index: Int): Int = result.getInt(index + 1)
        override fun getString(index: Int): String? = result.getString(index + 1)
    }

    companion object {
        /** 打开一个空的内存库并加载驱动；不做任何建表——建表由 [openTestDb] 统一负责。 */
        fun openMemory(): JdbcSqlDb {
            Class.forName("org.sqlite.JDBC")
            // 刻意不设 case_sensitive_like 之类的开关：sqlite-jdbc 的默认值与 Android 的 SQLite 一致，
            // 改默认值只会让测试环境与出厂环境悄悄分叉。
            return JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
        }
    }
}

/** JDBC 参数绑定：`PreparedStatement` 下标从 1 开始，`args` 从 0 开始。 */
private fun bind(statement: PreparedStatement, args: List<Any?>) {
    for (i in args.indices) {
        val index = i + 1
        when (val value = args[i]) {
            null -> statement.setNull(index, java.sql.Types.NULL)
            is String -> statement.setString(index, value)
            is Long -> statement.setLong(index, value)
            is Int -> statement.setInt(index, value)
            is Short -> statement.setShort(index, value)
            is Byte -> statement.setByte(index, value)
            is Double -> statement.setDouble(index, value)
            is Float -> statement.setFloat(index, value)
            is Boolean -> statement.setBoolean(index, value)
            is ByteArray -> statement.setBytes(index, value)
            else -> statement.setString(index, value.toString())
        }
    }
}
