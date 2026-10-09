package com.vertin.chongleme.ui.editor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.Intensity
import com.vertin.chongleme.data.photo.CaptureScratch
import com.vertin.chongleme.data.photo.PhotoIntake
import com.vertin.chongleme.theme.Colour
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.ui.BottomBarReserve
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.glass.GlassButton
import com.vertin.chongleme.ui.glass.GlassDialog
import com.vertin.chongleme.util.DayKey
import com.vertin.chongleme.util.todayKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 记录编辑页：全应用唯一往数据库里写内容的地方。
 *
 * `entry == null` 是新建，非 null 是编辑已有记录——两条路径共用同一份草稿（[EditorDraft]），
 * 差别只在保存时走 `add` 还是 `update` 加配图的增删。
 *
 * ## 两条配图链路，权限成本完全不同
 *
 * | | 入口 | 权限 | 回来的是什么 |
 * |---|---|---|---|
 * | 拍照 | `TakePicture` + FileProvider | `CAMERA` 运行时权限 | 相机把**原图**写进我们给的 `content://` |
 * | 相册 | `PickMultipleVisualMedia`（系统 Photo Picker） | **不需要任何权限** | 用户勾选的若干 `content://` |
 *
 * 刻意不用 `TakePicturePreview`：它只回传一张缩略图，对「用照片记录」这件事没有意义。
 *
 * ## 生命周期上真正要小心的三件事
 *
 * 1. **相机 App 在前台时本进程可能被回收。** 所以「正在等结果的那个中转文件路径」用
 *    `rememberSaveable` 存（不是 `remember`）：`rememberLauncherForActivityResult` 会把
 *    待处理的回调登记在 ActivityResultRegistry 里，重建后仍能收到结果，但那时只有字符串
 *    路径回得来——文件对象本身是回不来的。
 * 2. **中转文件不能在任何「看起来该清理」的时机删。** 组合被销毁不等于用户离开了编辑页
 *    （转屏、深色模式切换都会走一遍），所以这里**没有**在 `onDispose` 里删文件，
 *    而是在相机回调的三条分支（成功 / 取消 / 读失败）里删，配合进入页面时的一次性清扫。
 * 3. **取消是「什么都没发生」。** 新导入的照片先落盘、落库推迟到保存，所以取消时要把
 *    本次新增的文件一并删掉；已有记录的图不动。
 *
 * ## 关于「不自己 catch 全部异常」这条约定
 *
 * 编辑页是唯一必须亲手碰相机、位图解码和 FileProvider 的页面。位图相关的失败全部收在
 * [PhotoIntake] 里（它是数据层的工具类，返回 null 而不是抛）；这里只处理两种需要给用户
 * 一句话的情况：相机 App 不存在，以及数据库写入失败。两处都显式重抛
 * [CancellationException]，不吞协程取消。
 */
@Composable
fun EditorScreen(
    backdrop: Backdrop,
    repository: EntryRepository,
    entry: Entry?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val store = LocalPhotoStore.current
    val scope = rememberCoroutineScope()
    val today = remember { todayKey() }

    // 草稿用 rememberSaveable：写到一半被系统回收、或转屏，回来时字还在。
    var draft by rememberSaveable(stateSaver = EditorDraftSaver) {
        mutableStateOf(EditorDraft.from(entry, now = System.currentTimeMillis()))
    }

    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var photoProblem by remember { mutableStateOf<String?>(null) }
    var saveProblem by remember { mutableStateOf<String?>(null) }
    var askBeforeLeaving by remember { mutableStateOf(false) }

    /**
     * 已经在收拾东西准备关页了。
     *
     * 存在的理由是两条竞态，都发生在用户手快的时候：
     * - 导入还在跑时点「取消」：那张照片可能在我们清完文件之后才挂进草稿，文件就成了孤儿。
     * - 保存还在进行时点「取消」：数据库可能刚写进一条指向「已被删掉的文件」的配图。
     * 两个按钮都在这个标志与 [saving] 上设了闸，[intake] 也会在落盘后复查一次。
     */
    var leaving by remember { mutableStateOf(false) }

    // 正在等相机结果的临时文件（绝对路径）。见类注释第 1 条。
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }

    // 外壳的 entries 快照是异步到达的：编辑页有可能先以「新建」打开，随后真实记录才出现。
    // 只要草稿还没被用户动过，就让记录接管它——否则一次编辑会变成新增，多出一条重复记录。
    LaunchedEffect(entry?.id) {
        val loaded = entry
        if (loaded != null && draft.sourceId == null && !draft.dirty) {
            draft = EditorDraft.from(loaded, System.currentTimeMillis())
        }
    }

    // 上一次进程死在相机界面留下的中转文件，在这里收尾。当前正在等的那个不会被扫掉。
    LaunchedEffect(Unit) {
        val gone = withContext(Dispatchers.IO) {
            CaptureScratch.sweep(context, keepPath = capturePath)
        }
        val waiting = capturePath
        if (waiting != null && gone.contains(waiting)) capturePath = null
    }

    /**
     * 把一张外部图片读成草稿里的一张配图。
     *
     * 文件按**记录发生的时间**分片（而不是「现在」），补记上个月的照片才不会跑到本月的目录里。
     */
    suspend fun intake(uri: Uri): Boolean {
        val at = draft.occurredAt
        val photo = PhotoIntake.import(
            context = context,
            store = store,
            uri = uri,
            year = DayKey.of(at).year,
            month = DayKey.of(at).monthValue,
        )
        if (photo == null) return false
        if (leaving) {
            // 文件已经写下去了，但用户在我们读图的这段时间里决定不写了。
            // 立即把这份文件删掉，否则它会以「谁都不认识」的状态留在 filesDir 里。
            withContext(Dispatchers.IO) { store.delete(photo.relPath) }
            return false
        }
        draft = draft.edited { it.copy(pending = it.pending + photo) }
        return true
    }

    // ---- 拍照 ----

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = capturePath
        capturePath = null
        scope.launch {
            val file = path?.let(::File)
            try {
                if (ok && file != null && file.length() > 0L) {
                    if (!intake(CaptureScratch.uriFor(context, file))) {
                        photoProblem = "这张照片没能读进来。"
                    }
                } else if (ok) {
                    // 相机回了「成功」但没有内容：少数机型在存储满时会这样。
                    photoProblem = "相机没有写出照片文件。"
                }
                // ok == false：用户在相机里按了返回，什么都不说才是对的。
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                photoProblem = "这张照片没能读进来。"
            } finally {
                // 中转文件的一生到此为止：成功、取消、失败都经过这一行。
                CaptureScratch.discard(file)
            }
        }
    }

    /** 分配中转文件并唤起相机。 */
    val startCapture: () -> Unit = {
        photoProblem = null
        val file = CaptureScratch.newFile(context)
        if (file == null) {
            photoProblem = "临时文件建不出来，拍照暂时用不了。"
        } else {
            capturePath = file.absolutePath
            try {
                takePicture.launch(CaptureScratch.uriFor(context, file))
            } catch (_: ActivityNotFoundException) {
                // 没有系统相机的设备（部分平板、被停用的相机）
                capturePath = null
                CaptureScratch.discard(file)
                photoProblem = "这台设备上没有可用的相机。"
            }
        }
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCapture()
        } else {
            // 不把用户往设置页赶：配图还有相册这条路，而它一个权限都不要。
            photoProblem = "没有相机权限。配图用相册里的照片一样可以，不需要任何权限。"
        }
    }

    // ---- 相册 ----

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                photoProblem = null
                try {
                    var added = 0
                    for (uri in uris) {
                        if (draft.photoCount >= MaxPhotosPerEntry) break
                        if (intake(uri)) added++
                    }
                    if (added == 0) photoProblem = "这些照片没能读进来。"
                } finally {
                    importing = false
                }
            }
        }
    }

    val onCaptureClick: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCapture() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    val onPickClick: () -> Unit = {
        photoProblem = null
        // 只收图片；maxItems 用契约的默认值（系统上限），超出的部分在下面按张数截断，
        // 这样不必去赌不同系统版本对「一次最多选几张」的上限。
        pickPhotos.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

    // ---- 保存与离开 ----

    /** 丢弃这条草稿并关闭编辑页。**只删本次新导入的文件**，已有记录的配图一张不动。 */
    val discardAndLeave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                // 先取快照再删：leaving 已经立起来了，此刻在跑的导入会自己把文件删掉，
                // 不会再有新的文件挂进这份草稿。
                val dropped = draft.pending.map { it.relPath }
                val scratch = capturePath
                withContext(Dispatchers.IO) {
                    dropped.forEach { store.delete(it) }
                    CaptureScratch.discard(scratch)
                }
                capturePath = null
                onDone()
            }
        }
    }

    val requestClose: () -> Unit = close@{
        // 存的过程中不让走：数据库可能正写到一半，配图与记录会对不上。
        if (saving || leaving) return@close
        // 什么都没动就直接走，不弹框——每次打开编辑页又退出都问一遍很烦。
        if (draft.dirty) askBeforeLeaving = true else discardAndLeave()
    }

    val onSave: () -> Unit = save@{
        if (saving || importing || leaving) return@save
        saving = true
        saveProblem = null
        scope.launch {
            try {
                val id = draft.sourceId
                val stored = if (id == null) {
                    repository.add(
                        occurredAt = draft.occurredAt,
                        intensity = Intensity.coerce(draft.intensity),
                        durationMin = draft.durationMin,
                        mood = draft.mood,
                        note = draft.note.trim(),
                        photos = draft.pending,
                    )
                    true
                } else {
                    val updated = repository.update(
                        id = id,
                        occurredAt = draft.occurredAt,
                        intensity = Intensity.coerce(draft.intensity),
                        durationMin = draft.durationMin,
                        mood = draft.mood,
                        note = draft.note.trim(),
                    )
                    if (updated) {
                        // 配图的增删都在同一个入口里做完：
                        // 删除先落库成功再删文件，否则库里会留下指向空文件的记录。
                        withContext(Dispatchers.IO) {
                            draft.removed.forEach { photo ->
                                if (repository.removePhoto(photo.id)) store.delete(photo.relPath)
                            }
                        }
                        // 一张一张补图，成功一张就从草稿里摘掉：万一中途写库失败，
                        // 用户再点一次保存也不会把同一张插成两行。
                        draft.pending.forEach { draftPhoto ->
                            repository.addPhoto(id, draftPhoto)
                            draft = draft.copy(pending = draft.pending - draftPhoto)
                        }
                    }
                    updated
                }

                if (stored) {
                    draft = draft.copy(pending = emptyList(), removed = emptyList(), dirty = false)
                    CaptureScratch.discard(capturePath)
                    capturePath = null
                    onDone()
                } else {
                    // 记录在编辑期间消失了（另一处删掉了它）。草稿留着，用户可以取消或重试。
                    CaptureScratch.discard(capturePath)
                    capturePath = null
                    saveProblem = "这条记录已经不在了，改动没能存回去。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 图片文件已经落盘、草稿原样保留：用户可以再点一次保存，什么都不丢。
                CaptureScratch.discard(capturePath)
                capturePath = null
                saveProblem = "没能写进数据库，再点一次试试。"
            } finally {
                saving = false
            }
        }
    }

    // 系统返回键：和「取消」同一条路。放在这里注册，好让下面可见的确认框抢在它前面。
    BackHandler(enabled = !saving) { requestClose() }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            // 键盘弹起时整页上移：底部动作带跟着浮在键盘上方，不然「保存」会被 IME 盖住。
            .imePadding()
    ) {
        // 遮罩：编辑页是此刻唯一该看的东西。0.78 是个折中——再淡就盖不住下面的列表，
        // 再浓壁纸的光斑结构就没了，玻璃卡会显得像贴在纯黑上。
        // 这一层同时负责吃掉点击，避免手指落在下面页面的按钮上。
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawBehind { drawRect(Colour.BackgroundTop.copy(alpha = ScrimAlpha)) }
                .pointerInput(Unit) { detectTapGestures { } }
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding()
        ) {
            EditorHeader(
                title = if (draft.isNew) "记一笔" else "改一改",
                backdrop = backdrop,
                onCancel = requestClose,
                cancelEnabled = !saving && !leaving,
            )

            saveProblem?.let { message ->
                BasicText(
                    text = message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 12.sp, lineHeight = 18.sp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(2.dp))

                EditorTimeSection(
                    occurredAt = draft.occurredAt,
                    today = today,
                    onPickDate = { date ->
                        draft = draft.edited { it.copy(occurredAt = mergeOccurredAt(it.occurredAt, date = date)) }
                    },
                    onPickTime = { time ->
                        draft = draft.edited { it.copy(occurredAt = mergeOccurredAt(it.occurredAt, time = time)) }
                    },
                    onNow = {
                        draft = draft.edited { it.copy(occurredAt = System.currentTimeMillis()) }
                    },
                    backdrop = backdrop,
                )

                EditorDurationSection(
                    text = draft.durationText,
                    onTextChange = { text -> draft = draft.edited { it.copy(durationText = text) } },
                    backdrop = backdrop,
                )

                EditorIntensitySection(
                    value = draft.intensity,
                    onChange = { level -> draft = draft.edited { it.copy(intensity = level) } },
                    backdrop = backdrop,
                )

                EditorMoodSection(
                    mood = draft.mood,
                    onSelect = { mood -> draft = draft.edited { it.copy(mood = mood) } },
                    backdrop = backdrop,
                )

                EditorNoteSection(
                    note = draft.note,
                    onNoteChange = { note -> draft = draft.edited { it.copy(note = note) } },
                    backdrop = backdrop,
                )

                EditorPhotoSection(
                    existing = draft.existing,
                    pending = draft.pending,
                    importing = importing,
                    problem = photoProblem,
                    backdrop = backdrop,
                    onCapture = onCaptureClick,
                    onPick = onPickClick,
                    onRemoveExisting = { photo ->
                        // 先记下来，保存时才真删：这样「点了垃圾桶又反悔」不需要重新拍。
                        draft = draft.edited {
                            it.copy(existing = it.existing - photo, removed = it.removed + photo)
                        }
                    },
                    onRemovePending = { pendingPhoto ->
                        // 这张还没落库，直接删文件、从草稿里拿走。
                        draft = draft.edited { it.copy(pending = it.pending - pendingPhoto) }
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(pendingPhoto.relPath) }
                        }
                    },
                )

                // 底部预留：与今天/日历页给标签栏留的是同一个常量。
                // 编辑页盖住了标签栏，所以这一条留给自己的动作带。
                Spacer(Modifier.height(BottomBarReserve + navBottom))
            }
        }

        // 动作带。浮在内容之上，位置由 navigationBarsPadding 顶到手势条上方。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassButton(
                onClick = onSave,
                backdrop = backdrop,
                enabled = !saving && !importing && !leaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                BasicText(
                    text = if (saving) "正在存…" else "保存",
                    style = TextStyle(
                        color = LocalGlassContentColor.current,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }
        }

        // 离开确认。必须是这一层 Box 的最后一层，否则遮罩盖不满屏幕。
        GlassDialog(
            visible = askBeforeLeaving,
            onDismissRequest = { askBeforeLeaving = false },
            backdrop = backdrop,
        ) {
            BasicText(
                text = if (draft.isNew) "这一条还没存下" else "改动还没存下",
                style = TextStyle(
                    color = Colour.InkOnDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "现在走的话，刚写的和刚拍的都不会留下。",
                style = TextStyle(color = Colour.InkMutedOnDark, fontSize = 13.sp, lineHeight = 19.sp),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassButton(
                    onClick = {
                        askBeforeLeaving = false
                        discardAndLeave()
                    },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    BasicText(
                        text = "不留了",
                        style = TextStyle(color = LocalGlassContentColor.current, fontSize = 14.sp),
                    )
                }
                GlassButton(
                    onClick = { askBeforeLeaving = false },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    BasicText(
                        text = "继续写",
                        style = TextStyle(
                            color = LocalGlassContentColor.current,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

/** 遮罩浓度。见使用处的注释。 */
private const val ScrimAlpha = 0.78f
