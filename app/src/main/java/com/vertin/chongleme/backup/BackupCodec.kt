package com.vertin.chongleme.backup

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 备份里的一条记录。
 *
 * 与 [Entry] 的区别：这里是**哨兵 id**。导入时所有 id 都会被重新分配，
 * 所以 [srcId] 只用于把配图挂回正确的记录，不写进数据库。
 */
data class BackupEntry(
    val srcId: Long,
    val occurredAt: Long,
    val durationMin: Int?,
    val intensity: Int,
    val mood: Mood?,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val photos: List<BackupPhoto>,
)

/** 备份里的一张配图。id 同理不写库。 */
data class BackupPhoto(
    val srcId: Long,
    val relPath: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int,
    val createdAt: Long,
)

/** 解码结果。解析失败时带着原因，而不是抛异常给上层。 */
sealed interface DecodeResult {
    data class Success(val entries: List<BackupEntry>, val skippedPhotos: Int) : DecodeResult
    data class Failure(val reason: String) : DecodeResult
}

/**
 * 备份数据的 JSON 编解码。
 *
 * 全部是纯函数：不碰文件、不碰数据库、不碰 Android API，因此可以在 JVM 单测里
 * 直接喂畸形输入验证健壮性。
 *
 * 格式设计立场：**宽容读取、严格写入**。
 * - 写入时字段固定，且带 [FORMAT_VERSION]，将来改结构有据可依；
 * - 读取时任何字段缺失或类型不符都退化成默认值，而不是让整包导入失败。
 *   用户点「导入」时最不能接受的就是「文件有一处瑕疵 → 全部记录读不进来」。
 */
object BackupCodec {

    /** 备份格式版本。将来若改结构，靠它做分支处理。 */
    const val FORMAT_VERSION: Int = 1

    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"

    private const val KEY_SRC_ID = "id"
    private const val KEY_OCCURRED_AT = "occurredAt"
    private const val KEY_DURATION_MIN = "durationMin"
    private const val KEY_INTENSITY = "intensity"
    private const val KEY_MOOD = "mood"
    private const val KEY_NOTE = "note"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_PHOTOS = "photos"
    private const val KEY_REL_PATH = "relPath"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SORT_ORDER = "sortOrder"

    /** 强度合法区间，与 [com.vertin.chongleme.data.Intensity] 保持一致。 */
    private const val INTENSITY_MIN = 1
    private const val INTENSITY_MAX = 5

    fun encode(entries: List<Entry>): JSONObject {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put(KEY_SRC_ID, entry.id)
            obj.put(KEY_OCCURRED_AT, entry.occurredAt)
            entry.durationMin?.let { obj.put(KEY_DURATION_MIN, it) }
            obj.put(KEY_INTENSITY, entry.intensity)
            entry.mood?.let { obj.put(KEY_MOOD, it.name) }
            obj.put(KEY_NOTE, entry.note)
            obj.put(KEY_CREATED_AT, entry.createdAt)
            obj.put(KEY_UPDATED_AT, entry.updatedAt)

            val photos = JSONArray()
            entry.photos.sortedBy { it.sortOrder }.forEach { photo ->
                photos.put(
                    JSONObject().apply {
                        put(KEY_SRC_ID, photo.id)
                        put(KEY_REL_PATH, photo.relPath)
                        put(KEY_WIDTH, photo.width)
                        put(KEY_HEIGHT, photo.height)
                        put(KEY_SORT_ORDER, photo.sortOrder)
                        put(KEY_CREATED_AT, photo.createdAt)
                    }
                )
            }
            obj.put(KEY_PHOTOS, photos)
            array.put(obj)
        }
        return JSONObject().apply {
            put(KEY_VERSION, FORMAT_VERSION)
            put(KEY_ENTRIES, array)
        }
    }

    fun decode(text: String): DecodeResult {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return DecodeResult.Failure("不是合法的 JSON：${e.message}")
        }

        val version = root.optInt(KEY_VERSION, 0)
        if (version <= 0) {
            return DecodeResult.Failure("缺少 version 字段，无法确认备份格式")
        }
        if (version > FORMAT_VERSION) {
            return DecodeResult.Failure(
                "备份由更新版本的应用生成（格式 v$version，当前支持到 v$FORMAT_VERSION）"
            )
        }

        val rawEntries = root.optJSONArray(KEY_ENTRIES)
            ?: return DecodeResult.Failure("缺少 entries 字段")

        val entries = ArrayList<BackupEntry>(rawEntries.length())
        var skippedPhotos = 0

        for (i in 0 until rawEntries.length()) {
            val obj = rawEntries.optJSONObject(i) ?: continue
            val photos = ArrayList<BackupPhoto>()
            val rawPhotos = obj.optJSONArray(KEY_PHOTOS)
            if (rawPhotos != null) {
                for (j in 0 until rawPhotos.length()) {
                    val p = rawPhotos.optJSONObject(j) ?: continue
                    val relPath = p.stringOr("", KEY_REL_PATH)
                    if (relPath.isBlank()) {
                        // 没有路径的配图行无法定位文件，直接丢弃并计数
                        skippedPhotos++
                        continue
                    }
                    photos += BackupPhoto(
                        srcId = p.optLong(KEY_SRC_ID, 0L),
                        relPath = relPath,
                        width = p.optInt(KEY_WIDTH, 0),
                        height = p.optInt(KEY_HEIGHT, 0),
                        sortOrder = p.optInt(KEY_SORT_ORDER, photos.size),
                        createdAt = p.optLong(KEY_CREATED_AT, 0L),
                    )
                }
            }

            entries += BackupEntry(
                srcId = obj.optLong(KEY_SRC_ID, 0L),
                occurredAt = obj.optLong(KEY_OCCURRED_AT, 0L),
                durationMin = if (obj.has(KEY_DURATION_MIN) && !obj.isNull(KEY_DURATION_MIN)) {
                    obj.optInt(KEY_DURATION_MIN)
                } else {
                    null
                },
                intensity = obj.optInt(KEY_INTENSITY, INTENSITY_MIN)
                    .coerceIn(INTENSITY_MIN, INTENSITY_MAX),
                mood = parseMood(obj.stringOr("", KEY_MOOD)),
                note = obj.stringOr("", KEY_NOTE),
                createdAt = obj.optLong(KEY_CREATED_AT, 0L),
                updatedAt = obj.optLong(KEY_UPDATED_AT, 0L),
                photos = photos,
            )
        }

        if (entries.isEmpty()) {
            return DecodeResult.Failure("备份里没有任何记录")
        }
        return DecodeResult.Success(entries, skippedPhotos)
    }

    /**
     * 读取一个字符串字段。
     *
     * 刻意不用 `optString`：它会把数字、布尔值强制转成字符串（`42` → `"42"`），
     * 于是「类型不符」和「正常字符串」在行为上无法区分。这里显式要求 JSON 字符串，
     * 其余类型一律退化为默认值——符合本文件「宽容读取」的立场，也让行为可预期。
     */
    private fun JSONObject.stringOr(default: String, key: String): String {
        val value = opt(key)
        return if (value is String) value else default
    }

    /** 未知的心情名（比如旧版本写进去、新版本删掉了）退化成 null，而不是让整条记录失败。 */
    private fun parseMood(name: String): Mood? {
        if (name.isBlank()) return null
        return Mood.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
