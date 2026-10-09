// 顶层构建文件。
//
// 关于 Kotlin 版本：AGP 9.3.2 内置的 KGP 是 2.2.10，而 backdrop 库（AndroidLiquidGlass）
// 用 Kotlin 2.4.10 编译并发布了对应版本的元数据，低于 2.4.10 的编译器读不了。
// 因此这里用 buildscript classpath 显式把 KGP 抬到 2.4.10（见 gradle/libs 说明与
// 安卓APK构建指南 §4「Kotlin 版本怎么定」）。AGP 依赖的是同一坐标，冲突由 Gradle 解析为
// 我们声明的更高版本，不会出现两个 KGP 同时生效。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
