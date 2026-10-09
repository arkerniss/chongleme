package com.vertin.chongleme.data.db

/**
 * 查询结果里的一行。列下标从 **0** 开始（与 `android.database.Cursor` 一致）。
 *
 * 只暴露 DAO 真正用得到的四种取值，刻意不泄漏 `Cursor` / `ResultSet`：
 * 一旦 DAO 直接吃 `Cursor`，它就只能在 Android 运行时里被测试，
 * 而「SQL 到底对不对」这件事在 JVM 上根本验不了。
 */
interface SqlRow {
    fun isNull(index: Int): Boolean
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int

    /** 取字符串；SQL NULL 返回 null（不是空串）。 */
    fun getString(index: Int): String?
}

/** 可空列取值的小工具：SQL NULL → Kotlin null。 */
internal fun SqlRow.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

/**
 * 极薄的 SQL 执行门面：DAO 的**全部**数据库依赖就是这几个方法，
 * 而且它只负责「绑定参数 + 执行字符串」，**一句 SQL 都不生成**。
 *
 * 存在的理由（这是本项目最值得解释的一个设计决定）：
 * 出厂路径是 `SQLiteOpenHelper` + `SQLiteDatabase`，而 Android 单元测试里的
 * `android.jar` 是 mockable 的空壳——`SQLiteOpenHelper(ctx, null, ...)` 指向的内存库
 * 在纯 JVM 单测里**跑不起来**（方法体是 stub）。于是「增删改查、级联删除、事务回滚」
 * 这些必须验的东西只有两条路：引入 Robolectric（运行期要下载上百 MB 的 android-all，
 * 在受限沙箱里不可靠），或者把执行层抽成这么一层薄壳，
 * 让同一份 SQL 在 JVM 上跑真 SQLite（JDBC）。
 *
 * 于是职责被切开：
 * - SQL 字符串与行映射住在 [EntrySql] / [PhotoSql] / [EntryDao] / [PhotoDao]，
 *   与平台无关，可以在 JVM 上被真库验证；
 * - [AndroidSqlDb] 只把调用转成 `SQLiteDatabase`，是几十行没有分支的胶水。
 *
 * 参数化一律走 `?` 占位符，**永远不做字符串拼接**（拼接等于把用户输入交给 SQL 解析器）。
 */
interface SqlDb {

    /** 执行无返回值的语句（DDL、PRAGMA）。 */
    fun exec(sql: String, args: List<Any?> = emptyList())

    /** 执行 INSERT / UPDATE / DELETE，返回受影响行数。 */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** 执行 INSERT，返回新行的 rowid。失败必须抛异常，不能静默返回 -1。 */
    fun executeInsert(sql: String, args: List<Any?> = emptyList()): Long

    /** 查询并逐行映射。实现必须保证游标/语句被关闭。 */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>

    /** 取单个整数（`COUNT(*)`、`SUM(...)` 之类）；无行或 NULL 返回 null。 */
    fun queryScalarLong(sql: String, args: List<Any?> = emptyList()): Long?

    /**
     * 事务：块内抛异常 → 整体回滚并把异常继续抛出去；
     * 正常返回 → 提交，返回值即块内返回值。
     */
    fun <T> transaction(block: () -> T): T
}

/** 生成 `IN (?,?,?)` 里那串占位符。 */
internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
