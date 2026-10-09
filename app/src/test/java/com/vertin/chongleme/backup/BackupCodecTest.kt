package com.vertin.chongleme.backup

import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 JSON 编解码的表驱动测试。
 *
 * 这是全应用最不能出错的一段代码：用户点「导出」之后，那个 ZIP 就是他全部数据的
 * 唯一退路。所以这里刻意把「畸形输入」测得很重——真实世界里损坏的备份
 * 通常不是完全打不开，而是差了某个字段。
 */
class BackupCodecTest {

    private fun entry(
        id: Long = 1L,
        occurredAt: Long = 1_700_000_000_000L,
        durationMin: Int? = 12,
        intensity: Int = 3,
        mood: Mood? = Mood.Calm,
        note: String = "测试",
        photos: List<Photo> = emptyList(),
    ) = Entry(
        id = id,
        occurredAt = occurredAt,
        durationMin = durationMin,
        intensity = intensity,
        mood = mood,
        note = note,
        createdAt = occurredAt + 1,
        updatedAt = occurredAt + 2,
        photos = photos,
    )

    private fun photo(id: Long = 10L, entryId: Long = 1L, sortOrder: Int = 0) = Photo(
        id = id,
        entryId = entryId,
        relPath = "photos/2026/10/$id.webp",
        width = 2048,
        height = 1536,
        sortOrder = sortOrder,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `往返编码后字段完全一致`() {
        val original = listOf(
            entry(
                id = 7,
                durationMin = 25,
                intensity = 5,
                mood = Mood.Excited,
                note = "  带前后空格与\n换行  ",
                photos = listOf(photo(id = 71, entryId = 7, sortOrder = 0)),
            ),
            entry(id = 8, durationMin = null, intensity = 1, mood = null, note = ""),
        )

        val json = BackupCodec.encode(original).toString()
        val decoded = BackupCodec.decode(json)
        assertTrue("应当解码成功：$decoded", decoded is DecodeResult.Success)

        val roundTripped = (decoded as DecodeResult.Success).entries
        assertEquals(2, roundTripped.size)

        val first = roundTripped[0]
        assertEquals(7L, first.srcId)
        assertEquals(1_700_000_000_000L, first.occurredAt)
        assertEquals(25, first.durationMin)
        assertEquals(5, first.intensity)
        assertEquals(Mood.Excited, first.mood)
        assertEquals("  带前后空格与\n换行  ", first.note)
        assertEquals(1, first.photos.size)
        assertEquals("photos/2026/10/71.webp", first.photos[0].relPath)

        val second = roundTripped[1]
        assertNull("durationMin 为 null 时必须保持 null，不能变成 0", second.durationMin)
        assertNull("mood 为 null 时必须保持 null", second.mood)
        assertEquals("", second.note)
    }

    @Test
    fun `空记录列表编码后解码应报错而不是返回空成功`() {
        val json = BackupCodec.encode(emptyList()).toString()
        val decoded = BackupCodec.decode(json)
        assertTrue(
            "空备份应当明确失败，否则用户会以为导出成功了",
            decoded is DecodeResult.Failure
        )
    }

    @Test
    fun `非 JSON 文本应当返回失败而不是抛异常`() {
        listOf("", "不是 json", "{", "[1,2,3]", "null", "{\"version\":1").forEach { bad ->
            val decoded = BackupCodec.decode(bad)
            assertTrue("输入 `$bad` 应当失败", decoded is DecodeResult.Failure)
        }
    }

    @Test
    fun `缺少 version 字段应当拒绝`() {
        val decoded = BackupCodec.decode("""{"entries":[]}""")
        assertTrue(decoded is DecodeResult.Failure)
        assertTrue((decoded as DecodeResult.Failure).reason.contains("version"))
    }

    @Test
    fun `未来版本号的备份应当拒绝并说明原因`() {
        val decoded = BackupCodec.decode("""{"version":99,"entries":[{"occurredAt":1}]}""")
        assertTrue(decoded is DecodeResult.Failure)
        val reason = (decoded as DecodeResult.Failure).reason
        assertTrue("提示里应说明版本不匹配：$reason", reason.contains("99"))
    }

    @Test
    fun `字段缺失或类型不符时退化为默认值而不是整包失败`() {
        // 真实损坏的备份通常长这样：能打开，但某条记录少了个字段
        val json = """
            {
              "version": 1,
              "entries": [
                {"occurredAt": 1000},
                {"occurredAt": "不是数字", "intensity": "高", "mood": "不存在的情绪", "note": 42},
                {"durationMin": [1,2], "photos": "不是数组"}
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(json)
        assertTrue("应当仍然成功解出可用记录：$decoded", decoded is DecodeResult.Success)
        val entries = (decoded as DecodeResult.Success).entries
        assertEquals(3, entries.size)

        val first = entries[0]
        assertEquals(1000L, first.occurredAt)
        assertEquals(1, first.intensity)
        assertNull(first.mood)
        assertEquals("", first.note)

        val second = entries[1]
        assertNull("无法解析的 mood 应退化为 null", second.mood)
        assertTrue("越界/非法的 intensity 应被收敛到 1..5", second.intensity in 1..5)
        assertEquals("", second.note)

        assertTrue(
            "photos 字段类型不符时应退化为空列表，而不是让整条记录失败",
            entries[2].photos.isEmpty()
        )
    }

    @Test
    fun `强度越界值被收敛到合法区间`() {
        val json = """
            {"version":1,"entries":[
              {"occurredAt":1,"intensity":0},
              {"occurredAt":2,"intensity":99},
              {"occurredAt":3,"intensity":-5},
              {"occurredAt":4,"intensity":3}
            ]}
        """.trimIndent()

        val entries = (BackupCodec.decode(json) as DecodeResult.Success).entries
        assertEquals(1, entries[0].intensity)
        assertEquals(5, entries[1].intensity)
        assertEquals(1, entries[2].intensity)
        assertEquals(3, entries[3].intensity)
    }

    @Test
    fun `缺少 relPath 的配图行被丢弃并计数`() {
        val json = """
            {"version":1,"entries":[
              {"occurredAt":1,"photos":[
                {"relPath":"photos/2026/10/a.webp"},
                {"relPath":""},
                {},
                {"relPath":"photos/2026/10/b.webp"}
              ]}
            ]}
        """.trimIndent()

        val result = BackupCodec.decode(json) as DecodeResult.Success
        assertEquals(2, result.entries[0].photos.size)
        assertEquals("应当统计被丢弃的配图行", 2, result.skippedPhotos)
    }

    @Test
    fun `心情名匹配不区分大小写`() {
        val json = """{"version":1,"entries":[{"occurredAt":1,"mood":"calm"}]}"""
        val entries = (BackupCodec.decode(json) as DecodeResult.Success).entries
        assertEquals(Mood.Calm, entries[0].mood)
    }

    @Test
    fun `encode 会写入固定版本号`() {
        val obj = BackupCodec.encode(listOf(entry()))
        assertEquals(BackupCodec.FORMAT_VERSION, obj.getInt("version"))
        assertNotNull(obj.getJSONArray("entries"))
    }

    @Test
    fun `配图按 sortOrder 升序写出`() {
        val e = entry(photos = listOf(photo(id = 2, sortOrder = 2), photo(id = 1, sortOrder = 0)))
        val obj = BackupCodec.encode(listOf(e))
        val photos = obj.getJSONArray("entries").getJSONObject(0).getJSONArray("photos")
        assertEquals(0, photos.getJSONObject(0).getInt("sortOrder"))
        assertEquals(2, photos.getJSONObject(1).getInt("sortOrder"))
    }
}
