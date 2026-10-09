package com.vertin.chongleme.ui

import androidx.compose.runtime.Composable
import com.vertin.chongleme.AppTab
import com.vertin.chongleme.data.Entry
import com.vertin.chongleme.data.EntryRepository
import com.vertin.chongleme.data.Mood
import com.kyant.backdrop.Backdrop
import java.time.LocalDate

/**
 * 六个页面的冻结契约。
 *
 * 这里只放**签名约定与共用类型**，实现分散在 `ui/<name>/` 各自文件里。
 * 集中登记的理由：新增页面必须在这里露一次面，登记时就得想清楚它需要什么、
 * 能改变什么，从而让「页面之间不知道彼此存在」成为结构上的事实。
 *
 * ## 三条统一约定
 *
 * 1. **每个屏幕自己处理内边距**（`systemBarsPadding` / `displayCutoutPadding` /
 *    `imePadding`），并为底部标签栏留出高度：外壳不知道谁会滑到底部、
 *    谁会弹输入法，所以这个责任归属屏幕。底部预留用
 *    [BottomBarReserve] 这个常量，别各写各的数字。
 * 2. **所有写操作走 [EntryRepository] 的挂起函数**：屏幕不碰数据库、不碰文件、
 *    不自己开线程、不自己 catch 全部异常。
 * 3. **[Backdrop] 由外壳创建并传入**：全应用只允许存在一层 backdrop，
 *    组件内部绝不自己 `rememberLayerBackdrop()`。
 *
 * ## 冻结的入口签名
 *
 * ```
 * TodayScreen(backdrop, repository, entries, onRecord, onOpen)
 * EditorScreen(backdrop, repository, entry, onDone)
 * CalendarScreen(backdrop, entries)
 * GalleryScreen(backdrop, entries, onOpen)
 * BadgesScreen(backdrop, entries)
 * SettingsScreen(backdrop, repository, entries, onSelectTab)
 * ```
 *
 * 参数含义：
 * - `entries`：外壳已收集好的全量记录快照（最新在前，配图已挂好）。
 *   屏幕**不要**自己再收集仓库的 StateFlow。
 * - `onRecord`：请求新建一条。外壳负责把编辑页推上来——「要不要弹编辑页」是导航决定。
 * - `onOpen`：请求打开某条记录的编辑页。
 * - `onDone`：编辑完成（保存或取消），外壳据此关闭编辑页。
 * - `onSelectTab`：跳转到另一个 tab（目前只有设置页用得上，比如从设置引导回今天）。
 */

/**
 * 底部标签栏占掉的垂直空间。
 *
 * 标签栏自身高度 64dp + 上下 10dp 外边距 + 手势条高度，所以页面底部要留出
 * 大约这个量，否则列表最后一项会被玻璃遮住。外壳与页面共用同一个常量，
 * 改一处即可全局生效。
 */
val BottomBarReserve = androidx.compose.ui.unit.Dp(96f)

/**
 * 心情的中文标签。
 *
 * 放在 UI 层而不是写进 [Mood] 枚举：数据层不应该依赖展示文案——
 * 枚举名是持久化键，文案随时可能改。
 */
val Mood.displayName: String
    get() = when (this) {
        Mood.Calm -> "平静"
        Mood.Happy -> "愉快"
        Mood.Tired -> "疲惫"
        Mood.Stressed -> "紧绷"
        Mood.Excited -> "兴奋"
    }

/**
 * 把时间戳转成「今天 / 昨天 / 具体日期」的展示文案。
 *
 * 抽成共用函数是为了让五个页面用同一套口径：时间轴、日历、相册各写一遍
 * 一定会出现「今天」的判定不一致这种难看的问题。
 */
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}
