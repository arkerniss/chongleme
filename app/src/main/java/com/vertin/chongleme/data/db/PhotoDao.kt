package com.vertin.chongleme.data.db

import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.data.PhotoDraft

/**
 * `photo` 表的手写 SQL DAO。
 */
class PhotoDao(private val db: SqlDb) {

    /** 插入一张配图，返回新 rowid。 */
    fun insert(
        entryId: Long,
        relPath: String,
        width: Int,
        height: Int,
        sortOrder: Int = 0,
        createdAt: Long,
    ): Long = insertRow(entryId, relPath, width, height, sortOrder, createdAt)

    /**
     * 一次插入多张（新建记录时连着照片一起落库）。
     *
     * 整体在一个事务里：半套照片配一条记录，比一张照片都没有更难解释，
     * 而且会让「第几张」的顺序出现空洞。
     */
    fun insertAll(
        entryId: Long,
        drafts: List<PhotoDraft>,
        createdAt: Long,
        startSortOrder: Int = 0,
    ): List<Long> {
        if (drafts.isEmpty()) return emptyList()
        return db.transaction {
            drafts.mapIndexed { index, draft ->
                insertRow(
                    entryId = entryId,
                    relPath = draft.relPath,
                    width = draft.width,
                    height = draft.height,
                    sortOrder = startSortOrder + index,
                    createdAt = createdAt,
                )
            }
        }
    }

    /** 某条记录的配图，按用户排的顺序。 */
    fun byEntry(entryId: Long): List<Photo> =
        db.query(PhotoSql.SQL_SELECT_BY_ENTRY, listOf<Any?>(entryId)) { mapPhoto(it) }

    /**
     * 一次查多条记录的配图，返回 `entryId → 配图`（没有配图的记录不会出现在 map 里）。
     *
     * 分块的理由：SQLite 的绑定变量数量有上限（老版本 999），
     * 记录一多、`IN (?,?,…)` 就会直接报错。分块是廉价且必然需要的防护。
     */
    fun byEntries(entryIds: Collection<Long>): Map<Long, List<Photo>> {
        if (entryIds.isEmpty()) return emptyMap()
        val distinct = entryIds.distinct()
        val grouped = LinkedHashMap<Long, MutableList<Photo>>()
        for (chunk in distinct.chunked(MAX_IDS_PER_QUERY)) {
            val photos = db.query(PhotoSql.selectByEntries(chunk.size), chunk) { mapPhoto(it) }
            for (photo in photos) {
                grouped.getOrPut(photo.entryId) { ArrayList() }.add(photo)
            }
        }
        return grouped
    }

    fun delete(photoId: Long): Boolean =
        db.execute(PhotoSql.SQL_DELETE_BY_ID, listOf<Any?>(photoId)) > 0

    /** 删掉某条记录的全部配图，返回删除行数。 */
    fun deleteForEntry(entryId: Long): Int =
        db.execute(PhotoSql.SQL_DELETE_BY_ENTRY, listOf<Any?>(entryId))

    fun countForEntry(entryId: Long): Int =
        (db.queryScalarLong(PhotoSql.SQL_COUNT_BY_ENTRY, listOf<Any?>(entryId)) ?: 0L).toInt()

    /**
     * 重排某条记录的配图顺序，返回是否全部成功。
     *
     * 语义是「全有或全无」：只要有一个 id 不属于这条记录（拼错了、或者刚被别的路径删了），
     * 整批回滚并返回 false。部分成功会留下一个用户看不懂的顺序，
     * 而排序这种操作重试一次毫无代价。
     *
     * 回滚靠的是「在事务里抛异常」——[SqlDb.transaction] 只有这一个回滚触发点，
     * 用一个不带栈的私有信号异常做控制流，避免把控制流伪装成业务异常。
     */
    fun reorder(entryId: Long, orderedPhotoIds: List<Long>): Boolean {
        if (orderedPhotoIds.isEmpty()) return true
        return try {
            db.transaction {
                var moved = 0
                orderedPhotoIds.forEachIndexed { index, photoId ->
                    moved += db.execute(
                        PhotoSql.SQL_UPDATE_SORT_ORDER,
                        listOf<Any?>(index, photoId, entryId),
                    )
                }
                if (moved != orderedPhotoIds.size) throw ReorderRollback()
                true
            }
        } catch (_: ReorderRollback) {
            false
        }
    }

    private fun insertRow(
        entryId: Long,
        relPath: String,
        width: Int,
        height: Int,
        sortOrder: Int,
        createdAt: Long,
    ): Long = db.executeInsert(
        PhotoSql.SQL_INSERT,
        listOf<Any?>(entryId, relPath, width, height, sortOrder, createdAt),
    )

    private fun mapPhoto(row: SqlRow): Photo = Photo(
        id = row.getLong(PhotoSql.IDX_ID),
        entryId = row.getLong(PhotoSql.IDX_ENTRY_ID),
        relPath = row.getString(PhotoSql.IDX_REL_PATH).orEmpty(),
        // 尺寸列可为 NULL（例如从外部导入、还没解码过的图）；模型里是非空 Int，
        // 0 表示「未知」，UI 侧遇到 0 就不按原图比例预留高度。
        width = row.intOrNull(PhotoSql.IDX_WIDTH) ?: 0,
        height = row.intOrNull(PhotoSql.IDX_HEIGHT) ?: 0,
        sortOrder = row.getInt(PhotoSql.IDX_SORT_ORDER),
        createdAt = row.getLong(PhotoSql.IDX_CREATED_AT),
    )

    private companion object {
        /** 一次 `IN (…)` 最多绑多少个 id。取 400 而不是 999：给未来的其它绑定参数留余量。 */
        const val MAX_IDS_PER_QUERY: Int = 400
    }
}

/** 回滚信号：不填栈、不打日志（`writableStackTrace = false`），它只是一条控制流。 */
private class ReorderRollback : RuntimeException(null, null, false, false)
