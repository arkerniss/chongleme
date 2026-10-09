package com.vertin.chongleme.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Intensity
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import com.vertin.chongleme.data.PhotoDraft

/**
 * 编辑页正在编辑的内容。
 *
 * 为什么不直接用 [Entry]：编辑过程中的东西**还不是**数据层的形态——
 * 时长是一个用户正在敲的字符串（空串和「0」是两件事），新拍的图已经在磁盘上但还没落库，
 * 刚删掉的旧图还要等到保存时才真删。硬塞进 [Entry] 会逼着 UI 层提前编造 id 和 sortOrder。
 *
 * 三份配图列表是刻意分开的，保存时各走各的路（见 `EditorScreen` 的保存逻辑）：
 *
 * | 字段 | 含义 | 保存时 |
 * |---|---|---|
 * | [existing] | 这条记录原有的、用户没动的图 | 不动 |
 * | [pending] | 本次新导入的图（文件已在 `filesDir`，还没落库） | `addPhoto` |
 * | [removed] | 用户删掉的原有图（先记住，不立刻删） | `removePhoto` + 删文件 |
 *
 * 删除之所以推迟到保存，是为了让「取消」这个动作真的什么都不改变——
 * 用户点了垃圾桶又反悔，不该需要重新拍一张。
 *
 * [dirty] 只回答一个问题：用户到底动过没有。它是「确定要离开吗」那个对话框的唯一依据，
 * 也是「外壳的真实记录到达时能不能接管这份草稿」的前提。
 */
@Immutable
internal data class EditorDraft(
    /** 正在编辑的记录 id；`null` 表示这是一条还没落库的新记录。 */
    val sourceId: Long?,
    val occurredAt: Long,
    /** 时长的输入原文。保留字符串而不是 Int，是为了让「还没输入」和「输入了 0」可分辨。 */
    val durationText: String,
    val intensity: Int,
    val mood: Mood?,
    val note: String,
    val existing: List<Photo> = emptyList(),
    val pending: List<PhotoDraft> = emptyList(),
    val removed: List<Photo> = emptyList(),
    val dirty: Boolean = false,
) {

    /** 新建还是编辑。文案与保存路径都看它。 */
    val isNew: Boolean get() = sourceId == null

    /** 用户输入换算出来的时长；空输入、乱输入一律是「没填」。 */
    val durationMin: Int?
        get() = durationText.trim().toIntOrNull()?.coerceIn(0, MaxDurationMin)

    /** 现在挂在这条记录上的配图总数（已落库 + 本次新增）。 */
    val photoCount: Int get() = existing.size + pending.size

    /** 改一处内容，并标记「用户动过了」。所有编辑都必须走这里，否则离开时不会拦。 */
    fun edited(transform: (EditorDraft) -> EditorDraft): EditorDraft =
        transform(this).copy(dirty = true)

    companion object {

        /**
         * 时长的上限（分钟）。
         *
         * 一天 1440 分钟：输入框只收数字，但「14400 分钟」这种手滑不该进数据库——
         * 统计页的平均时长会被一个手指头毁掉。
         */
        const val MaxDurationMin: Int = 1440

        /** 新建记录时的默认强度：取 1..5 的中间，不替用户预判「这次有多猛」。 */
        const val DefaultIntensity: Int = 3

        /**
         * 从一条已有记录（或空白）生成初始草稿。
         *
         * [now] 由调用方给：新建记录的 `occurredAt` 默认「现在」，但用户随时可以改成昨天——
         * 补记是完全正常的用法，所以这里只给默认值，不给约束。
         */
        fun from(entry: Entry?, now: Long): EditorDraft = EditorDraft(
            sourceId = entry?.id,
            occurredAt = entry?.occurredAt ?: now,
            durationText = entry?.durationMin?.toString().orEmpty(),
            intensity = Intensity.coerce(entry?.intensity ?: DefaultIntensity),
            mood = entry?.mood,
            note = entry?.note.orEmpty(),
            existing = entry?.photos.orEmpty(),
        )
    }
}

/**
 * 让草稿在转屏、进程被回收后还能回来。
 *
 * 用 `rememberSaveable` 存而不只是 `remember`：笔记写到一半被系统回收，回来发现白写了，
 * 是这类应用最不能犯的错——用户下次就不会再打开它了。
 *
 * 编码方式：每个 [Photo] / [PhotoDraft] 展开成一个 `ArrayList<Any>`（数字与字符串都是
 * Bundle 认的类型），四层嵌套列表本身是 `Serializable`，所以整块能进 `Bundle`。
 * 解码一律走 `as? Number` / `as? String` 并带兜底，坏数据只会让某一张图消失，
 * 不会在恢复现场时抛异常。
 */
internal val EditorDraftSaver: Saver<EditorDraft, Any> = listSaver(
    save = { draft ->
        listOf(
            draft.sourceId ?: NoSourceId,
            draft.occurredAt,
            draft.durationText,
            draft.intensity,
            draft.mood?.name.orEmpty(),
            draft.note,
            encodePhotos(draft.existing),
            encodeDrafts(draft.pending),
            encodePhotos(draft.removed),
            if (draft.dirty) 1 else 0,
        )
    },
    restore = { values ->
        fun at(index: Int): Any? = values.getOrNull(index)
        EditorDraft(
            sourceId = (at(0) as? Number)?.toLong()?.takeIf { it != NoSourceId },
            occurredAt = (at(1) as? Number)?.toLong() ?: 0L,
            durationText = at(2) as? String ?: "",
            intensity = Intensity.coerce((at(3) as? Number)?.toInt() ?: EditorDraft.DefaultIntensity),
            mood = (at(4) as? String)?.takeIf { it.isNotEmpty() }?.let { name ->
                Mood.entries.firstOrNull { it.name == name }
            },
            note = at(5) as? String ?: "",
            existing = decodePhotos(at(6)),
            pending = decodeDrafts(at(7)),
            removed = decodePhotos(at(8)),
            dirty = (at(9) as? Number)?.toInt() == 1,
        )
    },
)

/** 「草稿不属于任何已有记录」的哨兵值。0 和负数都不是合法的 rowid。 */
private const val NoSourceId: Long = -1L

private fun encodePhotos(photos: List<Photo>): ArrayList<Any> {
    val rows = ArrayList<Any>(photos.size)
    photos.forEach { photo ->
        rows.add(
            arrayListOf<Any>(
                photo.id,
                photo.entryId,
                photo.relPath,
                photo.width.toLong(),
                photo.height.toLong(),
                photo.sortOrder.toLong(),
                photo.createdAt,
            )
        )
    }
    return rows
}

private fun decodePhotos(raw: Any?): List<Photo> {
    val rows = raw as? List<*> ?: return emptyList()
    return rows.mapNotNull { row ->
        val cells = row as? List<*> ?: return@mapNotNull null
        if (cells.size < 7) return@mapNotNull null
        val relPath = cells[2] as? String ?: return@mapNotNull null
        Photo(
            id = cells[0].asLongOrZero(),
            entryId = cells[1].asLongOrZero(),
            relPath = relPath,
            width = cells[3].asIntOrZero(),
            height = cells[4].asIntOrZero(),
            sortOrder = cells[5].asIntOrZero(),
            createdAt = cells[6].asLongOrZero(),
        )
    }
}

private fun encodeDrafts(drafts: List<PhotoDraft>): ArrayList<Any> {
    val rows = ArrayList<Any>(drafts.size)
    drafts.forEach { draft ->
        rows.add(
            arrayListOf<Any>(
                draft.relPath,
                draft.width.toLong(),
                draft.height.toLong(),
            )
        )
    }
    return rows
}

private fun decodeDrafts(raw: Any?): List<PhotoDraft> {
    val rows = raw as? List<*> ?: return emptyList()
    return rows.mapNotNull { row ->
        val cells = row as? List<*> ?: return@mapNotNull null
        if (cells.size < 3) return@mapNotNull null
        val relPath = cells[0] as? String ?: return@mapNotNull null
        PhotoDraft(
            relPath = relPath,
            width = cells[1].asIntOrZero(),
            height = cells[2].asIntOrZero(),
        )
    }
}

private fun Any?.asLongOrZero(): Long = (this as? Number)?.toLong() ?: 0L

private fun Any?.asIntOrZero(): Int = (this as? Number)?.toInt() ?: 0
