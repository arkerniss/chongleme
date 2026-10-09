package com.vertin.chongleme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.theme.GlassTokens
import com.vertin.chongleme.theme.LocalGlassContentColor
import com.vertin.chongleme.theme.ProvideGlassTokens
import com.vertin.chongleme.theme.Wallpaper
import com.vertin.chongleme.ui.LocalPhotoStore
import com.vertin.chongleme.ui.editor.EditorScreen
import com.vertin.chongleme.ui.glass.GlassTab
import com.vertin.chongleme.ui.glass.GlassTabBar
import com.vertin.chongleme.ui.tabs.TabContent
import com.vertin.chongleme.ui.tuner.TunerScreen

/**
 * 应用根组合。
 *
 * 命名说明：这个 composable 叫 `ChonglemeRoot` 而不是 `ChonglemeApp`，
 * 因为 `ChonglemeApp` 已经是 [android.app.Application] 子类的名字——
 * 同名会让 `setContent { ChonglemeApp() }` 产生重载歧义。
 *
 * 三个结构性决定：
 *
 * 1. **全应用只有一层 backdrop**。它在这里创建，向下传给背景层（作为折射源）
 *    与所有玻璃控件（作为采样源）。每多一层 backdrop 就多一次离屏渲染，
 *    这是本项目最硬的性能约束。
 *
 * 2. **仓库的 StateFlow 只在这里收集一次**，再作为 `entries` 分发给各页面。
 *    若每个页面各自订阅，同一份数据会有五份订阅、五份重组。
 *
 * 3. **不引入 Navigation 组件**。五个平级落点 + 一个编辑页，两个可保存状态就够；
 *    引入导航框架会多一层需要与 Compose 版本对齐的依赖。
 */
@Composable
fun ChonglemeRoot() {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as ChonglemeApp).container
    }
    val repository = container.repository

    // 冷启动读一次全量。仓库构造期不碰磁盘，所以这一步不能省。
    LaunchedEffect(repository) {
        repository.refresh()
    }

    val entries by repository.entries.collectAsStateWithLifecycle()

    // 底部选中的 tab。存 Int 而不是枚举本身：枚举不是可保存类型。
    var tabOrdinal by rememberSaveable { mutableStateOf(AppTab.Today.ordinal) }
    val tab = AppTab.fromOrdinal(tabOrdinal)

    // 正在编辑的记录 id。哨兵值语义见文件末尾两个常量。
    var editingId by rememberSaveable { mutableStateOf(NO_EDITOR) }

    // 调参浮层是否打开（仅 debug 用得上）。放在根部是因为它是整屏浮层，
    // 必须和背景、页面、标签栏处于同一个 Box 层。
    var showTuner by rememberSaveable { mutableStateOf(false) }

    val backdrop = rememberLayerBackdrop()

    // 玻璃参数提到根部持有：这样 debug 包的调参页拖动滑块时，**整个应用**都跟着变，
    // 而不只是调参浮层里的示例区。release 包里调参页整段被 R8 消除，此处恒为 Dark。
    //
    // 注意 isSystemInDarkTheme() 本身是 @Composable，必须先求值再喂给 remember——
    // 直接写进 remember 的 lambda 里编译器会报「@Composable 调用不在 @Composable 上下文」。
    val isDark = isSystemInDarkTheme()
    var tokens by remember(isDark) { mutableStateOf(GlassTokens.forTheme(isDark)) }

    // 照片根目录：深层组件（时间轴卡片、相册、全屏查看器）通过这个局部值拿到它，
    // 不必把 PhotoStore 一路当参数传下去。
    val photoStore = container.photoStore

    CompositionLocalProvider(LocalPhotoStore provides photoStore) {
        ProvideGlassTokens(tokens) {
            Box(Modifier.fillMaxSize()) {

                // 第一层：背景，同时是折射源
                Wallpaper(backdrop = backdrop)

                // 第二层：当前页面
                TabContent(
                    tab = tab,
                    backdrop = backdrop,
                    repository = repository,
                    entries = entries,
                    onRecord = { editingId = NEW_ENTRY },
                    onOpen = { entry -> editingId = entry.id },
                    onSelectTab = { selected -> tabOrdinal = selected.ordinal },
                    onOpenTuner = { showTuner = true },
                )

                // 第三层：底部玻璃标签栏
                AppTabBar(
                    tab = tab,
                    onSelect = { selected -> tabOrdinal = selected.ordinal },
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // 第四层：编辑页，覆盖全屏，自己处理返回
                if (editingId != NO_EDITOR) {
                    val isNew = editingId == NEW_ENTRY
                    EditorScreen(
                        backdrop = backdrop,
                        repository = repository,
                        entry = if (isNew) null else entries.firstOrNull { it.id == editingId },
                        onDone = { editingId = NO_EDITOR },
                    )
                }

                // 第五层：调参页（仅 debug）。
                //
                // 用 TunerScreen 而不是 TunerEntryPoint：后者自己持有 token，
                // 调参时只影响它自己的子树，用户看到的不是整个应用的真实效果。
                // 这里由根部持有 tokens，滑块一拖全应用实时跟着变。
                // 浮层必须在根部这一层——它是整屏浮层，塞进列表项会被父约束压扁。
                if (BuildConfig.DEBUG && showTuner) {
                    TunerScreen(
                        visible = true,
                        tokens = tokens,
                        onTokensChange = { tokens = it },
                        backdrop = backdrop,
                        onDismiss = { showTuner = false },
                    )
                }
            }
        }
    }
}

/**
 * 底部标签栏。
 *
 * 内边距分两处：`navigationBarsPadding` 躲开手势条，`padding` 让玻璃胶囊
 * 不贴屏幕边缘——玻璃贴边会失去「悬浮在内容之上」的感觉，看起来像一条实心工具栏。
 */
@Composable
private fun AppTabBar(
    tab: AppTab,
    onSelect: (AppTab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val tabs = AppTab.entries
    GlassTabBar(
        selectedIndex = tab.ordinal,
        onTabSelected = { index -> onSelect(AppTab.fromOrdinal(index)) },
        backdrop = backdrop,
        tabCount = tabs.size,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        tabs.forEach { item ->
            GlassTab(
                selected = item == tab,
                onClick = { onSelect(item) },
            ) {
                TabLabel(item.label)
            }
        }
    }
}

/**
 * 底部标签的文字。
 *
 * 用文字而不是图标：五个两字标签在底部栏里已能一眼分辨，而自绘一套图标
 * 需要单独的设计投入，仓促上马只会得到一看就是凑数的图形。
 * 选用系统的 Medium 字重加一点字距，是在没有品牌字体时让文字显得「被排过版」
 * 最省的做法。颜色由 [LocalGlassContentColor] 给出——选中态由 [GlassTab] 决定。
 */
@Composable
private fun TabLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = LocalGlassContentColor.current,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        ),
    )
}

/** 编辑页关闭。 */
private const val NO_EDITOR: Long = -1L

/** 编辑页打开，且是新建（而不是编辑某条已有记录）。 */
private const val NEW_ENTRY: Long = 0L
