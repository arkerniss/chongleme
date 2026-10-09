package com.vertin.chongleme.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.vertin.chongleme.data.PhotoDraft
import com.vertin.chongleme.data.PhotoStore
import com.vertin.chongleme.util.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 外部图片 → 应用私有配图 的**唯一**转换点。
 *
 * 三件事必须一次做完，漏掉任何一件都会在用户设备上变成「几百条记录吃满存储」：
 *
 * 1. **解码前就定好采样率**。相机原图动辄 4000px 起步、5–10MB，而记录配图只需要在手机上看得清。
 *    先整图解码再缩放等于白付一次内存峰值——低端机上那就是一次 OOM。
 *    采样因子由 [sampleSizeFor] 给出，它保证「最长边 / 因子 ≤ [MaxEdge]」。
 * 2. **编码成 WebP**：同质量下体积大约只有 JPEG 的一半，而且不需要引任何依赖。
 * 3. **写进 [PhotoStore]**，拿到相对路径才可能落库（[PhotoDraft] 就是这个形态）。
 *
 * 失败一律返回 `null`，绝不抛：一张读不进来的照片不该让整条记录写不成。
 * 所以这个类里到处是 `catch`——它们是接口的一部分，不是草率。
 *
 * 调用方负责在 IO 线程上调 [import]（函数内部已经切到 [Dispatchers.IO]）。
 */
object PhotoIntake {

    /** 落盘后最长边的上限（像素）。 */
    const val MaxEdge: Int = 2048

    /**
     * WebP 有损编码质量。
     *
     * 85 是这类「自己看」的照片的常见落点：再高体积涨得比画质快，再低暗部开始出现块。
     */
    const val WebpQuality: Int = 85

    /** 照片统一存成有损 WebP，扩展名与实际编码保持一致。 */
    private const val Extension = "webp"

    /**
     * 把 [uri] 指向的图片读进来，降采样 + 转码后写进 [store]，返回可落库的 [PhotoDraft]。
     *
     * [year] / [month] 用来决定文件落在哪个分片目录：用**记录发生的时间**而不是「现在」，
     * 这样补记上个月的照片会和那一天的记录待在同一个目录里（见 [PhotoStore.allocateRelativePath]）。
     *
     * @return 成功时是一张已经落盘的配图；读不了、转不了、写不进都返回 `null`。
     */
    suspend fun import(
        context: Context,
        store: PhotoStore,
        uri: Uri,
        year: Int,
        month: Int,
        maxEdge: Int = MaxEdge,
    ): PhotoDraft? = withContext(Dispatchers.IO) {
        val decoded = decode(context, uri, maxEdge) ?: return@withContext null
        try {
            val bytes = decoded.toWebp() ?: return@withContext null
            val relPath = store.allocateRelativePath(year, month, Extension)
            if (!store.write(relPath, bytes)) {
                null
            } else {
                // 宽高取**降采样之后**的真实像素：列表页拿它算采样率，报原图尺寸会让它算小一档。
                PhotoDraft(relPath = relPath, width = decoded.width, height = decoded.height)
            }
        } finally {
            // 解码出来的位图可能有好几 MB，交给 GC 之前先显式还回去。
            decoded.recycle()
        }
    }

    /**
     * 解码并降采样。
     *
     * 两条路：API 28 起用 [ImageDecoder]（自己认 EXIF 方向、认 HEIC 这类的现代格式），
     * 更早的系统退回 [BitmapFactory] + 手工补方向。
     */
    private fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 必须指定软件位图：硬件位图的像素留在 GPU 上，compress() 读不回来。
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // 这个回调在真正解码之前被调用，info 里已经是原图尺寸——采样率就是在这里定的。
                decoder.setTargetSampleSize(
                    sampleSizeFor(info.size.width, info.size.height, maxEdge)
                )
            }
        } else {
            decodeLegacy(context, uri, maxEdge)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // 几十兆的原图 + 低端机是真实存在的组合。返回 null 让用户看到「这张没能读进来」，
        // 比让整个编辑页崩掉好得多。
        null
    }

    /**
     * API 23–27 的兜底路径。
     *
     * 先把流落到一个临时文件，理由有两个，都是硬的：
     * - [BitmapFactory] 不认识 EXIF 方向，横拍竖拍都会读成「横着」；而框架版 [ExifInterface]
     *   在 API 23 上只吃文件路径，不吃流。
     * - 量尺寸和解码本来就要读两遍，`content://` 的流不保证能重复打开。
     *
     * 临时文件写在 cacheDir 根部并由本函数在 `finally` 里删掉；它不带扩展名，
     * 也不会和 [CaptureScratch] 的相机中转文件混在一起。
     */
    private fun decodeLegacy(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val scratch = try {
            File.createTempFile("intake-", ".img", context.cacheDir)
        } catch (_: Exception) {
            return null
        }
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
            if (!copied) return null

            // 第一遍：只读尺寸（inJustDecodeBounds 不分配像素内存）。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(scratch.absolutePath, bounds)

            // 第二遍：按算好的因子真正解码。
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(scratch.absolutePath, options) ?: return null
            return raw.applyOrientation(readOrientation(scratch))
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            scratch.delete()
        }
    }

    /** 读 EXIF 方向；读不到就当「正常」，不抛。 */
    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 按 EXIF 方向把位图摆正。
     *
     * 只处理真实照片里会出现的几种：旋转 90/180/270 与水平/垂直翻转。
     * `TRANSPOSE` / `TRANSVERSE` 基本只出现在合成图里，遇到就原样返回——
     * 为它们把主流程写复杂不划算。
     */
    private fun Bitmap.applyOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            else -> return this
        }
        val turned = try {
            Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        } catch (_: Exception) {
            return this
        } catch (_: OutOfMemoryError) {
            return this
        }
        if (turned != this) recycle()
        return turned
    }

    /**
     * 编码成有损 WebP。
     *
     * `CompressFormat.WEBP` 在 API 30 被标了废弃（官方希望用 `WEBP_LOSSY`），但 `WEBP_LOSSY`
     * 是 API 30 才有的常量，而本项目 minSdk 是 23。旧常量在所有版本上的行为都是「有损 WebP」，
     * 所以这里继续用它，把废弃告警摁在本地。
     */
    @Suppress("DEPRECATION")
    private fun Bitmap.toWebp(): ByteArray? = try {
        // 预算容量：2048 长边的 WebP 大约 1MB，够省掉 ByteArrayOutputStream 的几次扩容，
        // 又不会被一张超大图撑爆（上限 8MB）。
        val guess = ((width.toLong() * height.toLong()) / 4L).coerceIn(1L shl 16, 8L shl 20).toInt()
        val out = ByteArrayOutputStream(guess)
        if (compress(Bitmap.CompressFormat.WEBP, WebpQuality, out)) out.toByteArray() else null
    } catch (_: Exception) {
        null
    }
}
