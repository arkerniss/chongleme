package com.vertin.chongleme.data.photo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 相机中转文件的收口处。
 *
 * ## 为什么必须经过一个文件
 *
 * 拍照用的是 `ActivityResultContracts.TakePicture`：「相机 App 把原图写到调用方给的 URI 上」。
 * 另一个看起来更省事的 `TakePicturePreview` 只回传一张缩略图（几十 KB，放大了全是马赛克），
 * 对「用照片记录」这件事没有意义——用户按下快门是想要那张照片，不是想要一个图标。
 *
 * ## 为什么放在 cacheDir
 *
 * 相机原图只是一次性的中转物：读进 [PhotoIntake] 变成 WebP 之后它就再也不该存在。
 * 放在 `filesDir` 会跟着用户数据一起被统计、被备份、被「还有多少空间」的账算进去；
 * 放外部存储又要处理存储权限。`cacheDir` 的系统语义正好是「随时可以回收」，
 * 我们只负责比系统更早一步把它删掉。
 *
 * 代价是需要在 `res/xml/file_paths.xml` 里声明一条 `<cache-path>`，
 * 否则 [FileProvider] 会因为「这个文件不在任何已声明的目录下」直接抛异常。
 *
 * ## authority 不能硬编码
 *
 * debug 构建的 `applicationId` 带 `.debug` 后缀，所以 authority 只有从
 * `context.packageName` 现算才对得上 Manifest 里的 `${applicationId}.fileprovider`。
 */
object CaptureScratch {

    /** `cacheDir` 下的子目录，与 `file_paths.xml` 里 `<cache-path name="capture" path="capture/" />` 对应。 */
    private const val Dir = "capture"

    /** 与 Manifest 里 `${applicationId}.fileprovider` 的后缀部分。 */
    private const val AuthoritySuffix = ".fileprovider"

    /**
     * 超过这个年纪的中转文件一定是残留。
     *
     * 正常流程从「按下快门」到「读进照片」只有几秒；能跨过一小时的，只可能是进程被杀之后
     * 再也没人认领的文件（连相机 App 都不会握着一个 URI 一小时）。
     */
    const val StaleAfterMillis: Long = 60L * 60L * 1000L

    /**
     * 给这次拍照分配一个中转文件。
     *
     * 目录会在需要时创建——[FileProvider] 只负责把文件映射成 `content://`，
     * 它不会替我们建目录，而相机 App 拿着 `MODE_CREATE` 打开文件时父目录必须已经存在。
     *
     * @return 分配失败（磁盘满、cacheDir 不可写）返回 `null`，调用方应当据此放弃这次拍照。
     */
    fun newFile(context: Context, now: Long = System.currentTimeMillis()): File? = try {
        val dir = File(context.cacheDir, Dir)
        if (!dir.isDirectory && !dir.mkdirs()) {
            null
        } else {
            val file = File(dir, "capture-$now.jpg")
            // 先建出来：文件存在与否决定了 URI 是否可用，也决定相机写不进时我们能不能
            // 立刻分辨出「写失败」和「读失败」。
            if (file.createNewFile() || file.isFile) file else null
        }
    } catch (_: Exception) {
        null
    }

    /** 中转文件的 `content://` URI。相机 App 靠它写入，我们靠它读回来。 */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AuthoritySuffix, file)

    /** 删掉一个中转文件。文件本来就不在时也算成功（幂等）。 */
    fun discard(file: File?): Boolean = try {
        file == null || !file.exists() || file.delete()
    } catch (_: Exception) {
        false
    }

    /** 按路径删除，给 `rememberSaveable` 存下来的那个字符串路径用。 */
    fun discard(path: String?): Boolean = discard(path?.let(::File))

    /**
     * 收拾 `cacheDir/capture/` 里没人认领的中转文件。
     *
     * 调用时机是编辑页进入组合时。策略只有一条规则，但两个分支都必要：
     *
     * - [keepPath] 指向的文件**而且**还年轻 → 留着。这是「相机 App 正在前台、本进程被回收」
     *   的场景：重建后的组合还要靠它把照片读回来，现在删掉等于把用户刚拍的那张扔掉。
     * - 其余一律删。包括不属于本次会话的文件（上次进程死在相机界面留下的），
     *   也包括 [keepPath] 但已经老过 [staleAfter] 的——那种文件不可能再有回调来认领它。
     *
     * @return 实际删掉的绝对路径。调用方若发现 [keepPath] 在其中，应当把它一并忘掉。
     */
    fun sweep(
        context: Context,
        keepPath: String?,
        now: Long = System.currentTimeMillis(),
        staleAfter: Long = StaleAfterMillis,
    ): List<String> {
        val files = File(context.cacheDir, Dir).listFiles() ?: return emptyList()
        val removed = ArrayList<String>(files.size)
        for (file in files) {
            if (!file.isFile) continue
            val stillAwaiting = file.absolutePath == keepPath && now - file.lastModified() <= staleAfter
            if (stillAwaiting) continue
            if (file.delete()) removed += file.absolutePath
        }
        return removed
    }
}
