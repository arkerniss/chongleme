package com.vertin.chongleme.backup

import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.Mood
import com.vertin.chongleme.data.PhotoStore
import com.vertin.chongleme.data.db.EntriesSchema
import com.vertin.chongleme.data.db.JdbcSqlDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 备份服务层的**端到端往返**测试。
 *
 * ## 为什么这个文件必须存在
 *
 * 137 个用例里有 20 个在测备份，但它们全部止步于 `BackupCodec`（JSON ↔ 对象）
 * 与 `BackupArchive`（字节 ↔ ZIP）。而真正决定「用户能不能把数据拿回来」的
 * `BackupService` —— 它的筛选、去重、命名、落盘顺序、计数语义 —— **一行都没被测过**。
 * 一次独立评审把这条列为最重要的缺口，理由很直接：
 * 本应用没有云同步、没有账号，这个导出的 ZIP 就是全部退路，
 * 而退路是唯一没有被证据支撑的那条路径。
 *
 * 这里用真实的 SQLite（sqlite-jdbc 跑生产同一批 SQL 常量）与真实的临时目录，
 * 唯一被替换的是 Android `Context`（`BackupService` 为此提供了可注入的构造器）。
 */
class BackupServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repository: EntryRepository
    private lateinit var photoStore: PhotoStore
    private lateinit var service: BackupService

    private fun setUpService() {
        photoStore = PhotoStore(temp.newFolder("files"))
        // 建库方式与出厂库一一对应：同一份 EntriesSchema 的 PRAGMA 与 DDL。
        // 不复用 data/db 包里的 openTestDb()，是为了让这个文件的依赖一眼可见。
        val db = JdbcSqlDb.openMemory().apply {
            exec(EntriesSchema.SQL_FOREIGN_KEYS_ON)
            EntriesSchema.STATEMENTS.forEach { exec(it) }
        }
        repository = EntryRepository(db, clock = { NOW })
        // 目录只建一次并固定返回：TemporaryFolder.newFolder 每次调用都会
        // 尝试新建同名目录并抛异常，而 export() 可能被多次调用。
        val exportDir = temp.newFolder("export")
        service = BackupService(
            repository = repository,
            photoStore = photoStore,
            exportDirectoryProvider = { exportDir },
        )
    }

    /** 造一条带配图的记录并落库，返回它的 id。 */
    private fun seedEntry(
        occurredAt: Long,
        intensity: Int,
        mood: Mood?,
        note: String,
        photoCount: Int,
    ): Long = runBlocking {
        val drafts = (1..photoCount).map { index ->
            val relPath = photoStore.allocateRelativePath(2026, 10)
            photoStore.write(relPath, ByteArray(64) { index.toByte() })
            com.vertin.chongleme.data.PhotoDraft(relPath = relPath, width = 800, height = 600)
        }
        repository.add(
            occurredAt = occurredAt,
            intensity = intensity,
            durationMin = 12,
            mood = mood,
            note = note,
            photos = drafts,
        )
    }

    // ── 核心承诺：导出 → 清空 → 导入 → 全套回来 ─────────────────

    @Test
    fun `导出后清空再导入，记录与配图完整还原`() = runBlocking {
        setUpService()
        seedEntry(occurredAt = 1_700_000_000_000L, intensity = 4, mood = Mood.Calm, note = "第一条", photoCount = 2)
        seedEntry(occurredAt = 1_700_500_000_000L, intensity = 2, mood = Mood.Tired, note = "第二条", photoCount = 1)

        val before = repository.snapshot()
        assertEquals(2, before.size)
        val photosBefore = before.sumOf { it.photos.size }
        assertEquals(3, photosBefore)
        // 记下全部配图文件的字节指纹，导入后要逐一对上
        val beforeFingerprints = before.flatMap { it.photos }.map { photoStore.read(it.relPath)!!.toList() }.toSet()

        // 导出
        val exported = service.export()
        assertTrue("导出应当成功：$exported", exported is BackupService.ExportResult.Success)
        exported as BackupService.ExportResult.Success
        assertEquals(2, exported.entryCount)
        assertEquals(3, exported.photoCount)
        assertTrue("导出的 ZIP 应当真实存在于磁盘上", exported.file.isFile)
        assertTrue(exported.bytes > 0)

        // 清空（模拟用户重装或换机）
        repository.deleteAll()
        photoStore.deleteAll()
        assertEquals(0, repository.snapshot().size)
        assertTrue(photoStore.listAllRelativePaths().isEmpty())

        // 导入
        val result = service.import(exported.file.readBytes())
        assertTrue("导入应当成功：$result", result is BackupService.ImportResult.Success)
        result as BackupService.ImportResult.Success
        assertEquals(2, result.entriesAdded)
        assertEquals(3, result.photosAdded)
        assertEquals(0, result.photosMissing)

        // 逐项核对：记录数、发生时间（不能漂移）、心情、备注、配图数、配图字节
        val after = repository.snapshot()
        assertEquals(2, after.size)
        assertEquals(
            before.map { it.occurredAt }.sorted(),
            after.map { it.occurredAt }.sorted(),
        )
        assertEquals(
            before.map { it.note }.sorted(),
            after.map { it.note }.sorted(),
        )
        assertEquals(
            before.map { it.mood }.sortedBy { it?.name },
            after.map { it.mood }.sortedBy { it?.name },
        )
        assertEquals(
            before.map { it.intensity }.sorted(),
            after.map { it.intensity }.sorted(),
        )

        val photosAfter = after.sumOf { it.photos.size }
        assertEquals(3, photosAfter)

        // 每一张配图都必须真的能从磁盘读出来，且内容与导出前一致
        val afterFingerprints = after.flatMap { it.photos }.mapNotNull { photo ->
            photoStore.read(photo.relPath)?.toList()
        }.toSet()
        assertEquals("配图字节必须完整还原", beforeFingerprints, afterFingerprints)
    }

    // ── 「只追加不覆盖」的核心承诺 ──────────────────────────────

    @Test
    fun `同一份备份导入两次得到两份记录，且已有数据一条不丢`() = runBlocking {
        setUpService()
        seedEntry(occurredAt = 1_700_000_000_000L, intensity = 3, mood = Mood.Happy, note = "原始", photoCount = 1)

        val exported = service.export() as BackupService.ExportResult.Success
        val zip = exported.file.readBytes()

        val first = service.import(zip) as BackupService.ImportResult.Success
        val second = service.import(zip) as BackupService.ImportResult.Success

        assertEquals(1, first.entriesAdded)
        assertEquals(1, second.entriesAdded)

        val all = repository.snapshot()
        assertEquals("原始 + 两次导入 = 3 条，一条都不该被覆盖", 3, all.size)
        assertEquals(
            "原始的备注必须在",
            true,
            all.any { it.note == "原始" },
        )
        // 三条记录各自的配图文件路径必须互不相同（否则就是覆盖了别人的文件）
        val paths = all.flatMap { it.photos }.map { it.relPath }
        assertEquals("配图路径不得重复", paths.size, paths.distinct().size)
        assertEquals(3, paths.size)
        // 且每个路径都能读出内容
        assertTrue(paths.all { photoStore.read(it) != null })
    }

    @Test
    fun `导入不会复用备份里的文件路径`() = runBlocking {
        setUpService()
        seedEntry(occurredAt = 1_700_000_000_000L, intensity = 3, mood = null, note = "", photoCount = 1)
        val originalPath = repository.snapshot().first().photos.first().relPath

        val zip = (service.export() as BackupService.ExportResult.Success).file.readBytes()
        service.import(zip)

        val importedPaths = repository.snapshot().flatMap { it.photos }.map { it.relPath }
        assertNotEquals(
            "导入必须重新分配文件名，复用原路径会覆盖既有文件",
            setOf(originalPath),
            importedPaths.toSet(),
        )
        // 原始文件仍在
        assertNotNull(photoStore.read(originalPath))
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    fun `没有记录时导出明确失败而不是产出空包`() = runBlocking {
        setUpService()
        val result = service.export()
        assertTrue("空库导出应当失败", result is BackupService.ExportResult.Failure)
    }

    @Test
    fun `导入畸形字节返回失败而不是抛异常`() = runBlocking {
        setUpService()
        seedEntry(occurredAt = 1_700_000_000_000L, intensity = 3, mood = null, note = "", photoCount = 0)

        listOf(
            ByteArray(0),
            byteArrayOf(1, 2, 3, 4, 5),
            "这不是压缩包".toByteArray(),
        ).forEach { garbage ->
            val result = service.import(garbage)
            assertTrue("垃圾输入应当返回失败：$result", result is BackupService.ImportResult.Failure)
        }
        // 失败不能影响已有数据
        assertEquals(1, repository.snapshot().size)
    }

    @Test
    fun `配图文件缺失时计数正确，且记录仍然落库`() = runBlocking {
        setUpService()
        seedEntry(occurredAt = 1_700_000_000_000L, intensity = 3, mood = Mood.Calm, note = "有图", photoCount = 2)

        val exported = service.export() as BackupService.ExportResult.Success
        val zip = exported.file.readBytes()

        // 重建一个更严格的场景：把 ZIP 里的一张配图换成一个不存在的路径引用
        val content = BackupArchive.read(zip)
        val manifest = content.manifest!!
        val decoded = BackupCodec.decode(manifest) as DecodeResult.Success
        val firstEntry = decoded.entries.first()

        // 手动组一个新包：manifest 指向两条配图，但只提供其中一条的字节
        val missingPath = firstEntry.photos.last().relPath
        val manifestWithMissing = BackupCodec.encode(
            repository.snapshot()
        ).toString().replace(missingPath, "photos/2026/10/does-not-exist.webp")

        val rebuilt = BackupArchive.write(
            manifestJson = manifestWithMissing,
            photos = content.photos.filterKeys { it != missingPath },
        )

        repository.deleteAll()
        photoStore.deleteAll()

        val result = service.import(rebuilt)
        assertTrue("导入应当成功：$result", result is BackupService.ImportResult.Success)
        result as BackupService.ImportResult.Success

        assertEquals(1, result.entriesAdded)
        assertEquals("只有一张图能落盘", 1, result.photosAdded)
        assertEquals("另一张应被计为缺失", 1, result.photosMissing)
        assertEquals(
            "记录本身必须落库，只是少了一张图——不能因为缺图就丢整条记录",
            1,
            repository.snapshot().size,
        )
        assertEquals(1, repository.snapshot().first().photos.size)
    }

    @Test
    fun `formatVersionOf 能读出格式版本`() {
        val manifest = BackupCodec.encode(
            listOf(
                com.vertin.chongleme.data.Entry(
                    id = 1L, occurredAt = 1L, durationMin = null, intensity = 3,
                    mood = null, note = "", createdAt = 1L, updatedAt = 1L,
                )
            )
        ).toString()
        assertEquals(BackupCodec.FORMAT_VERSION, BackupService.formatVersionOf(manifest))
        assertEquals(null, BackupService.formatVersionOf("不是 json"))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
