# 冲了吗

一个**离线优先的私人记录本**。数据只存在你自己的手机里，没有账号、没有云同步、**连网络权限都没有**。

界面用真正的液体玻璃（Liquid Glass）效果：玻璃面板会**折射**背后的内容，而不只是加一层模糊。

---

## 它是什么

- 记录每一次的时间、时长、强度、心情和备注
- 可以拍照或从相册选图，绑定到某条记录
- 日历热力图、周/月频率、连续天数——把记录变成看得见的自我认知
- 徽章系统，让记录这件事不至于枯燥
- 数据导出/导入：换机、重装都不会丢

## 它不是什么

- **不是健康建议工具。** 应用不说教、不评判，不出现「坚持」「自律」这类措辞
- **不需要联网。** `AndroidManifest.xml` 里刻意没有 `INTERNET` 权限，物理上无法联网
- **数据不上云。** 连系统自带的云备份和换机迁移都被显式排除（见 `data_extraction_rules.xml`）

---

## 界面

> 截图待补（真机验收后加到这里）

单 Activity + edge-to-edge，底部是可拖动的玻璃标签栏：

| 页面 | 内容 |
|---|---|
| 今天 | 玻璃沙漏（今日进度）、记一笔、今日时间轴 |
| 日历 | 月历热力图、连续天数、频率统计 |
| 相册 | 照片瀑布流、全屏查看 |
| 成就 | 徽章网格 |
| 设置 | 备份导出/导入、主题、清空数据 |

---

## 技术栈

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.12.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 23 |

液体玻璃效果来自 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
（Maven 坐标 `io.github.kyant0:backdrop`），详见 [NOTICE](NOTICE)。

### 两个值得说明的技术选择

**为什么 Kotlin 是 2.4.10 而不是 AGP 内置的 2.2.10？**
`backdrop` 库用 Kotlin 2.4.10 编译并发布了对应版本的元数据，低于 2.4.10 的编译器读不了。
所以顶层 `build.gradle.kts` 用 `buildscript` classpath 把 KGP 显式抬到 2.4.10。

**为什么手写 SQLite 而不用 Room？**
KSP 最新版依赖 `kotlin-stdlib` 2.3.20，与上面要求的 Kotlin 2.4.10 存在版本冲突风险。
本应用只有 2 张表、十来个查询，KSP 的收益不抵一类构建失败风险，因此用
`SQLiteOpenHelper` + 手写 DAO，JSON 用 Android 内置的 `org.json`。

---

## 构建

需要 JDK 21 与 Android SDK（`platforms;android-37.0` + `build-tools;37.0.0`）。

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

首次构建会下载约 1–2 GB 依赖。国内网络慢的话，可以在**本机**（不要提交）
建 `~/.gradle/init.d/mirror.init.gradle.kts` 把仓库指向国内镜像，
仓库内的 `settings.gradle.kts` 保持使用官方 `google()` / `mavenCentral()`，
这样别人 clone 下来也能构建。

### 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖统计口径（连续天数、热力图、频率）、徽章规则、JSON 编解码与 DAO 的边界情况：
空列表、跨月、跨年、时区边界、闰日、连续中断、单日多条。

---

## 数据与隐私

| 数据 | 位置 |
|---|---|
| 记录 | `filesDir` 下的 SQLite 库 |
| 照片 | `filesDir/photos/<年>/<月>/<uuid>.webp`，长边压到 2048px |

- 应用**没有**网络权限
- `allowBackup=false`，且 `data_extraction_rules.xml` 排除云备份与换机迁移
- 卸载应用 = 数据彻底消失，**这是设计意图**
- 想保留数据就用设置里的「导出备份」，会生成一个 ZIP 放在
  `Android/data/com.vertin.chongleme/files/backup/`，可用文件管理器直接拷走

---

## 版本与发布

版本号在 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
每次发布打一个 tag，APK 作为 Release 附件：

```bash
./构建APK-compose.sh                  # 构建并归档
git tag v0.2.0 && git push origin v0.2.0
gh release create v0.2.0 <apk路径> --title "v0.2.0" --notes "..."
```

> ⚠️ release 包目前用 Android 默认的 debug keystore 签名，**仅供自己装机使用**。
> 要正式分发需自建 keystore 并配置 `signingConfigs`，否则换签名会有覆盖安装冲突。

---

## 已知限制

- **Android 12 及以下没有折射与色散**：折射依赖运行时着色器（API 33+）。
  Android 12 会降级为模糊 + 高光，Android 11 及以下只剩半透明表面色
- v1 没有应用锁（指纹/面容），架构上已预留
- 仅中文界面

---

## 许可

[MIT](LICENSE)。第三方组件的版权与许可见 [NOTICE](NOTICE)。
