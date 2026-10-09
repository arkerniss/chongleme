package com.vertin.chongleme.ui.settings

import android.content.Intent
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
import androidx.core.content.FileProvider
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.AppTab
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
    onSelectTab: (AppTab) -> Unit,
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
            onClick = {
                scope.launch {
                    busy = true
                    statusMessage = "正在导出…"
                    when (val result = backup.export()) {
                        is BackupService.ExportResult.Failure -> {
                            statusMessage = "导出失败：${result.reason}"
                        }

                        is BackupService.ExportResult.Success -> {
                            val kb = result.bytes / 1024
                            statusMessage = "已导出 ${result.entryCount} 条记录、" +
                                    "${result.photoCount} 张配图（${kb}KB）\n" +
                                    result.file.absolutePath
                            shareExported(context, result.file.absolutePath)
                        }
                    }
                    busy = false
                }
            },
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = !busy,
        ) {
            BasicText(
                text = "导出备份",
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
            "备份会导出成 ZIP，可用文件管理器从 " +
                    "Android/data/${context.packageName}/files/backup/ 拷走。" +
                    "导入只追加记录，永远不会覆盖或删除已有数据。"
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
                        entries.forEach { repository.delete(it.id) }
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
 * 导出完成后拉一下系统分享面板。
 *
 * 走 `FileProvider` 而不是直接给路径：Android 7 起直接把 `file://` 路径塞进 Intent
 * 会抛 `FileUriExposedException`，而且用户在多数的分享目标里也确实需要一个 URI。
 * 分享失败不影响导出本身——文件已经在磁盘上了，所以这里吞掉异常但不静默：
 * 上方的状态文案始终显示文件路径。
 */
private fun shareExported(context: android.content.Context, absolutePath: String) {
    try {
        val file = java.io.File(absolutePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "保存备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        // 没有可用的分享目标时保持安静：文件已经导出成功，状态栏里也有路径
    }
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
