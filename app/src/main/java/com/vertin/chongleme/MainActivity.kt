package com.vertin.chongleme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vertin.chongleme.theme.Wallpaper

/**
 * 唯一的 Activity。
 *
 * 全应用使用 edge-to-edge 绘制：状态栏与导航栏都透明，界面自己处理内边距
 * （各屏用 systemBarsPadding / imePadding / displayCutoutPadding 组合）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}

@Composable
private fun App() {
    // T0 骨架：先立起「壁纸 + 玻璃」这两层地基，后续页面都长在它们之上。
    // 当 T2 完成玻璃组件库后，这里会替换为真正的导航宿主。
    Box(Modifier.fillMaxSize()) {
        Wallpaper()
    }
}
