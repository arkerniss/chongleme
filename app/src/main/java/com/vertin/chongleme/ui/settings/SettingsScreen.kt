package com.vertin.chongleme.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.BuildConfig
import com.vertin.chongleme.backup.BackupService
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassCard
import com.vertin.chongleme.ui.glass.GlassDialog
import kotlinx.coroutines.launch

/**
 * 设置。
 *
 * 这一页存在的唯一理由是「数据安全」——本应用没有账号、没有云同步，
 * 用户手里那个导出的 ZIP 就是全部退路。所以导出/导入放在最上面，
 * 且每一步都给出具体结果（文件在哪、导入了多少条），而不是只弹一个「成功」。
 *
 * 所有破坏性操作都要过一层确认弹窗，且确认按钮用明确的动词（「清空」而不是「确定」）。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    repository: EntryRepository,
    entries: List<Entry>,
    onOpenTuner: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 复用容器里那一份 PhotoStore，而不是各页自建
    val photoStore = LocalPhotoStore.current
    val backup = remember(repository, photoStore) {
        BackupService(context.applicationContext, repository, photoStore)
    }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    /**
     * 导出到用户自己挑的位置（系统文件选择器）。
     *
     * 这是**推荐**的导出方式：Android 11 起 `Android/data/` 下的内容不再允许
     * 第三方应用浏览，所以「导出到应用目录，再用文件管理器拷走」在多数手机上走不通。
     * 让用户直接选保存位置（下载目录、网盘客户端等）才是真正可用的路径。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusMessage = "正在导出…"
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    backup.exportTo(out)
                } ?: BackupService.ExportResult.Failure("无法写入所选位置")
            }.getOrElse { error ->
                BackupService.ExportResult.Failure("写入失败：${error.message ?: error.javaClass.simpleName}")
            }

            statusMessage = when (result) {
                is BackupService.ExportResult.Failure -> "导出失败：${result.reason}"
                is BackupService.ExportResult.Success ->
                    "已保存 ${result.entryCount} 条记录、${result.photoCount} 张配图"
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusMessage = "正在导入…"
            val bytes = backup.readBytes(uri).getOrElse { error ->
                busy = false
                statusMessage = "读取失败：${error.message}"
                return@launch
            }
            when (val result = backup.import(bytes)) {
                is BackupService.ImportResult.Failure -> {
                    statusMessage = "导入失败：${result.reason}"
                }

                is BackupService.ImportResult.Success -> {
                    val extra = buildString {
                        if (result.photosMissing > 0) append("，${result.photosMissing} 张配图缺失")
                        if (result.photosSkipped > 0) append("，${result.photosSkipped} 张配图被跳过")
                    }
                    statusMessage =
                        "已导入 ${result.entriesAdded} 条记录、${result.photosAdded} 张配图$extra"
                }
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = BottomBarReserve + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = "设置",
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        )

        SectionLabel("数据")

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            StatLine("记录总数", entries.size.toString())
            StatLine("配图总数", entries.sumOf { it.photos.size }.toString())
        }

        GlassButton(
            onClick = { exportLauncher.launch(backupFileName()) },
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !busy,
        ) {
            BasicText(
                text = "导出备份（选择保存位置）",
                style = TextStyle(color = Colour.InkOnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }

        GlassButton(
            onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !busy,
        ) {
            BasicText(
                text = "导入备份",
                style = TextStyle(color = Colour.InkOnDark, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }

        Hint(
            "导出会弹系统文件选择器，你可以存到下载目录、网盘客户端或任何找得到的地方——" +
                    "导出的 ZIP 里包含全部记录与照片。\n" +
                    "导入只追加记录，永远不会覆盖或删除已有数据；同一个备份导入两次会得到两份记录。"
        )

        statusMessage?.let { message ->
            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = message,
                    style = TextStyle(
                        color = Colour.InkOnDark,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        }

        SectionLabel("隐私")

        Hint(
            "本应用没有网络权限，数据只存在这台手机里。" +
                    "系统云备份与换机迁移也已被显式关闭，卸载应用等于删除全部数据。"
        )

        SectionLabel("危险操作")

        GlassButton(
            onClick = { confirmClear = true },
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !busy && entries.isNotEmpty(),
        ) {
            BasicText(
                text = "清空全部数据",
                style = TextStyle(
                    color = Colour.IntensityOn,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        if (BuildConfig.DEBUG) {
            SectionLabel("开发者")
            GlassButton(
                onClick = onOpenTuner,
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                BasicText(
                    text = "打开玻璃调参页",
                    style = TextStyle(
                        color = Colour.InkOnDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Hint(
                "调参页可实时调整折射高度、折射量、模糊半径、色散与表面色，" +
                        "拖动时整个应用会立刻跟着变。调好后复制参数文本发给开发者即可固化。" +
                        "此按钮与调参页只存在于 debug 包，正式包里会被整段移除。"
            )
        }

        AboutBlock()
    }

    GlassDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        backdrop = backdrop,
    ) {
        BasicText(
            text = "清空全部数据？",
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = "将删除全部 ${entries.size} 条记录与所有配图，此操作不可撤销。" +
                    "建议先导出备份。",
            style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 13.sp, lineHeight = 20.sp),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            GlassButton(
                onClick = { confirmClear = false },
                backdrop = backdrop,
                modifier = Modifier.height(44.dp),
            ) {
                BasicText(text = "取消", style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 14.sp))
            }
            GlassButton(
                onClick = {
                    confirmClear = false
                    scope.launch {
                        busy = true
                        val removed = entries.size
                        // 一条 DELETE 语句清库，而不是逐条删：逐条会在写调度器上
                        // 触发 N 次「删一行 + 全表重读」，几百条记录时界面会静止十几秒，
                        // 用户以为卡死。先删库、再删文件，顺序不能反——反过来的话
                        // 中途失败会留下一批没有任何记录引用的图片。
                        repository.deleteAll()
                        photoStore.deleteAll()
                        statusMessage = "已清空 $removed 条记录"
                        busy = false
                    }
                },
                backdrop = backdrop,
                modifier = Modifier.height(44.dp),
            ) {
                BasicText(
                    text = "清空",
                    style = TextStyle(color = Colour.IntensityOn, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}


/**
 * 导出时预填的文件名。
 *
 * 用本地时间而不是 UTC：这是在用户自己的文件管理器里看到的字符串，
 * 用他所在时区的时间才不会对不上「我刚几点导的」。
 */
private fun backupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    return "冲了吗-backup-$stamp.zip"
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
        style = TextStyle(
            color = Colour.InkMutedOnDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        ),
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 14.sp),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = value,
            style = TextStyle(
                color = Colour.InkOnDark,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(
        text = text,
        modifier = Modifier.padding(horizontal = 6.dp),
        style = TextStyle(
            color = Colour.InkMutedOnDark.copy(alpha = 0.75f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
    )
}

@Composable
private fun AboutBlock() {
    Column(Modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp)) {
        BasicText(
            text = "冲了吗 v${BuildConfig.VERSION_NAME}",
            style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 12.sp),
        )
        Spacer(Modifier.height(3.dp))
        BasicText(
            text = "液体玻璃效果来自 AndroidLiquidGlass（Apache-2.0）",
            style = TextStyle(color = Colour.InkMutedOnDark.copy(alpha = 0.6f), fontSize = 11.sp),
        )
    }
}
