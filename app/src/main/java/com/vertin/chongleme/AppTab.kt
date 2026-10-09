package com.vertin.chongleme

/**
 * 底部导航的五个落点。
 *
 * 刻意不引入 Navigation 组件：本应用只有「五个平级落点 + 一个编辑页」，
 * 没有深链、没有嵌套返回栈、没有多 pane 需求。用 [AppTab.ordinal] 当状态
 * 比引入一个导航框架更少代码，也少一层需要与 Compose 版本对齐的依赖。
 *
 * 标签用中文而不是图标：自绘图标集需要一笔不小的设计投入，而五个两字标签
 * 在底部栏里已经能一眼分辨，也不会引入「每个 tab 配一个 emoji」那类视觉噪音。
 */
enum class AppTab(val label: String) {
    Today("今天"),
    Calendar("日历"),
    Gallery("相册"),
    Badges("成就"),
    Settings("设置");

    companion object {
        fun fromOrdinal(ordinal: Int): AppTab =
            entries.getOrElse(ordinal) { Today }
    }
}
