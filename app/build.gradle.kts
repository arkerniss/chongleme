plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vertin.chongleme"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.vertin.chongleme"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // AGP 默认不签名 release 包，产出的是 app-release-unsigned.apk，装不上。
            // 这里复用 AGP 自己生成/管理的 debug keystore 给 release 签名：
            // 「能直接装到自己手机上」是自用工具的核心需求，而生成与管理 keystore
            // 需要用户手动执行 keytool 并保管密码，对一个自用应用是不必要的门槛。
            //
            // ⚠️ 这不适合任何形式的分发：debug keystore 是公开且固定的，
            // 而且换机器就会变，升级时签名冲突。要正式分发必须自建 keystore，
            // 见 README「版本与发布」。
            signingConfig = signingConfigs.getByName("debug")

            // 本项目用到 R8 的规则很少：Compose 自带 consumer rules，
            // backdrop 是 Compose 库同样自带。保持默认 optimize 配置即可。
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo.include = false
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        // 调参页用 BuildConfig.DEBUG 做编译期闸门：它是编译期常量，
        // R8 会把整个调参页从 release 包里整段消除。AGP 9 默认不再生成 BuildConfig。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // minSdk 23 上没有 java.time，统计模块全部依赖它做日期运算。
        // 用 desugaring 把 java.time 补进来，而不是退回 Calendar 那套易错 API。
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 液体玻璃：backdrop 提供绘制与效果，shapes 提供 SDF 形状
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material.ripple)

    implementation(libs.coil.compose)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    // JVM 单测里的 android.jar 是 stub，跑不了真 SQLite。
    // sqlite-jdbc 让 DAO 的 CRUD、级联删除、事务回滚能在本机真验，
    // 而不是靠 Robolectric（它会在测试运行期下载约 100MB 的 android-all）。
    testImplementation(libs.sqlite.jdbc)
    // 同理：android.jar 里的 org.json 是「not mocked」桩，BackupCodec 一调 put 就抛。
    // 用一份 API 等价的真实现替换，被验证的才是生产里真正跑的那段代码。
    testImplementation(libs.org.json)
}
