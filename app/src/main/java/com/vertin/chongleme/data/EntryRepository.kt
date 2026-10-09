package com.vertin.chongleme.data

import android.content.Context
import com.vertin.chongleme.data.db.AndroidSqlDb
import com.vertin.chongleme.data.db.EntriesDb
import com.vertin.chongleme.data.db.EntryDao
import com.vertin.chongleme.data.db.PhotoDao
import com.vertin.chongleme.data.db.SqlDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 进程级唯一的写调度器。
 *
 * 关键细节：`Dispatchers.IO.limitedParallelism(1)` **每次调用都返回一个新的「1 并发视图」**，
 * 两个视图之间并不互斥。所以必须共享同一个实例，否则「写入串行化」这个承诺是假的
 * （两次写入会各占一个槽位并行跑进 SQLite）。
 */
private val SingleWriter: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * 数据层对上层（T3 页面）唯一暴露的入口。
 *
 * 契约冻结：`entries: StateFlow<List<Entry>>`。
 *
 * 三个设计决定：
 * 1. **全量快照而非分页**：这是一个人的私人记录，量级在千条以内，
 *    一次读完放进 `StateFlow`，UI 侧所有筛选/统计都是纯内存计算，
 *    不需要为分页引入 `Paging` 这种重量级依赖。
 * 2. **读也走写调度器**：所有数据库访问（含 [refresh]）都排在同一个单并发调度器上。
 *    SQLite 自己当然能处理并发读，但那样会出现「写了一半时读到一个中间态」的窗口——
 *    比如记录已插入、照片还没插入，列表就会闪一下「没有配图」。排队是免费的。
 * 3. **每次变更后整表重读**：看似浪费，实际上把「缓存一致性」这颗雷直接拆掉了。
 *    千条以内的全表读 + 一次 `IN` 查询在毫秒级，换来的是 UI 永远不会看到旧数据。
 *
 * 生命周期：[open] 出来的实例应当由 `Application` 持有并在整个进程内复用；
 * 同时存在两个实例不会破坏数据（SQLite 有锁），但会多两份 `StateFlow`，界面刷新会各自为政。
 */
class EntryRepository(
    private val db: SqlDb,
    private val entryDao: EntryDao = EntryDao(db),
    private val photoDao: PhotoDao = PhotoDao(db),
    private val writeDispatcher: CoroutineDispatcher = SingleWriter,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** 全部记录（最新在前），每条都带好了配图。冷启动时先调一次 [refresh]。 */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** 当前内存快照，给「统计」这类纯同步场景用，避免为了拿个列表去启动协程。 */
    fun snapshot(): List<Entry> = _entries.value

    /** 从数据库重读全量并更新 [entries]。 */
    suspend fun refresh(): List<Entry> = withContext(writeDispatcher) { reload() }

    /**
     * 新建一条记录（可选地连着配图一起），返回新 rowid。
     *
     * [occurredAt] 由调用方给：补记昨天的记录是完全正常的用法，
     * 仓库层不许拿「现在」冒充「事情发生的时间」。
     * `createdAt` / `updatedAt` 才是仓库层用注入的 [clock] 盖的时间戳。
     */
    suspend fun add(
        occurredAt: Long,
        intensity: Int,
        durationMin: Int? = null,
        mood: Mood? = null,
        note: String = "",
        photos: List<PhotoDraft> = emptyList(),
    ): Long = mutate {
        val now = clock()
        db.transaction {
            val id = entryDao.insert(
                occurredAt = occurredAt,
                durationMin = durationMin,
                intensity = intensity,
                mood = mood,
                note = note,
                createdAt = now,
            )
            if (photos.isNotEmpty()) photoDao.insertAll(id, photos, now)
            id
        }
    }

    /** 编辑一条记录（不碰配图）。返回是否真的改到了行。 */
    suspend fun update(
        id: Long,
        occurredAt: Long,
        intensity: Int,
        durationMin: Int? = null,
        mood: Mood? = null,
        note: String = "",
    ): Boolean = mutate {
        entryDao.update(
            id = id,
            occurredAt = occurredAt,
            durationMin = durationMin,
            intensity = intensity,
            mood = mood,
            note = note,
            updatedAt = clock(),
        )
    }

    /** 删除一条记录；配图由外键级联删除。返回是否真的删到了行。 */
    suspend fun delete(id: Long): Boolean = mutate { entryDao.delete(id) }

    /**
     * 清空全部记录（单事务）。返回删掉的记录数。
     *
     * 存在的理由：设置页原本是 `entries.forEach { delete(it.id) }`，而 [mutate]
     * 每次都会整表重读一遍，于是清空 N 条记录要做 N 次全表扫描 —— O(N²)。
     * 几百条记录时界面会静止十几秒，用户会以为卡死并强杀进程。
     *
     * 配图由 `entry` 的外键级联删除；磁盘上的图片文件不归这里管，
     * 由调用方接着调 `PhotoStore.deleteAll()`。先删库再删文件，顺序不能反。
     */
    suspend fun deleteAll(): Int = mutate { entryDao.deleteAll() }

    /** 给已有记录补一张图，返回新 photo id。 */
    suspend fun addPhoto(entryId: Long, draft: PhotoDraft): Long =
        mutate { photoDao.insert(entryId, draft.relPath, draft.width, draft.height, createdAt = clock()) }

    suspend fun addPhoto(entryId: Long, relPath: String, width: Int, height: Int): Long =
        addPhoto(entryId, PhotoDraft(relPath, width, height))

    suspend fun removePhoto(photoId: Long): Boolean = mutate { photoDao.delete(photoId) }

    /** 重排配图顺序；任一 id 不属于该记录则整批回滚并返回 false。 */
    suspend fun reorderPhotos(entryId: Long, orderedPhotoIds: List<Long>): Boolean =
        mutate { photoDao.reorder(entryId, orderedPhotoIds) }

    private suspend fun <T> mutate(block: () -> T): T = withContext(writeDispatcher) {
        val result = block()
        reload()
        result
    }

    private fun reload(): List<Entry> {
        val all = entryDao.all()
        val merged = if (all.isEmpty()) {
            all
        } else {
            val photosByEntry = photoDao.byEntries(all.map { it.id })
            all.map { entry ->
                val photos = photosByEntry[entry.id]
                if (photos == null) entry else entry.copy(photos = photos)
            }
        }
        _entries.value = merged
        return merged
    }

    companion object {
        /**
         * 打开出厂仓库：真数据库文件 `chongleme.db`。
         *
         * 这里只构造 [EntriesDb] 与一层壳，**不会碰磁盘**——真正的 `getWritableDatabase()`
         * 推迟到第一次查询，所以放在 `Application.onCreate` 里也不会拖慢启动。
         */
        fun open(context: Context, clock: () -> Long = System::currentTimeMillis): EntryRepository =
            EntryRepository(AndroidSqlDb(EntriesDb(context)), clock = clock)
    }
}
