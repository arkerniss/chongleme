package com.vertin.chongleme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * 唯一的 Activity。
 *
 * 全应用 edge-to-edge：状态栏与导航栏透明，界面自己处理内边距
 * （各屏用 systemBarsPadding / imePadding / displayCutoutPadding 组合）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ChonglemeRoot()
        }
    }
}
