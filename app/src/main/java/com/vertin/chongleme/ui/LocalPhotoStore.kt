package com.vertin.chongleme.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.vertin.chongleme.data.PhotoStore

/**
 * 让深层组件（时间轴卡片、相册网格、全屏查看器）拿到 [PhotoStore]，
 * 而不必把它一路当参数传下去。
 *
 * 用 [staticCompositionLocalOf] 而不是 `compositionLocalOf`：这个值在应用生命周期内
 * 不会变，静态版本不建立订阅关系，读取成本更低。
 *
 * 默认值抛异常而不是给一个空实现——忘记提供 PhotoStore 是个编程错误，
 * 应该在第一次渲染时就明确指出，而不是显示一片空白图片位。
 */
val LocalPhotoStore = staticCompositionLocalOf<PhotoStore> {
    error("LocalPhotoStore 未被提供：请在根组合处用 PhotoStore(context) 提供它。")
}
