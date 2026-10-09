package com.vertin.chongleme.ui.tabs

import androidx.compose.runtime.Composable
import com.kyant.backdrop.Backdrop
import com.vertin.chongleme.AppTab
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.ui.badges.BadgesScreen
import com.vertin.chongleme.ui.calendar.CalendarScreen
import com.vertin.chongleme.ui.gallery.GalleryScreen
import com.vertin.chongleme.ui.settings.SettingsScreen
import com.vertin.chongleme.ui.today.TodayScreen

/**
 * 把「当前选中的 tab」翻译成具体页面。
 *
 * 这里只做分发，不持有任何状态：选中项、编辑页开关都由外壳持有。
 * 页面拿到的 `entries` 是外壳收集好的全量快照，页面**不要**自己再订阅仓库，
 * 否则同一份数据会有五份订阅与五份重组。
 *
 * 六个页面的冻结签名见 `ui/Screens.kt`。
 */
@Composable
fun TabContent(
    tab: AppTab,
    backdrop: Backdrop,
    repository: EntryRepository,
    entries: List<Entry>,
    onRecord: () -> Unit,
    onOpen: (Entry) -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onOpenTuner: () -> Unit,
) {
    when (tab) {
        AppTab.Today -> TodayScreen(
            backdrop = backdrop,
            repository = repository,
            entries = entries,
            onRecord = onRecord,
            onOpen = onOpen,
        )

        AppTab.Calendar -> CalendarScreen(
            backdrop = backdrop,
            entries = entries,
        )

        AppTab.Gallery -> GalleryScreen(
            backdrop = backdrop,
            entries = entries,
            onOpen = onOpen,
        )

        AppTab.Badges -> BadgesScreen(
            backdrop = backdrop,
            entries = entries,
        )

        AppTab.Settings -> SettingsScreen(
            backdrop = backdrop,
            repository = repository,
            entries = entries,
            onSelectTab = onSelectTab,
            onOpenTuner = onOpenTuner,
        )
    }
}
