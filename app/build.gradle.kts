plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ========== 版本号（支持 CI 参数注入） ==========
// CI 环境通过 -PversionName=x.x.x -PversionCode=xxx 注入
// 本地开发使用 gradle.properties 中的默认值
val versionNameProp = (project.findProperty("versionName") as? String).orEmpty().ifEmpty { "2.6.1" }
val versionCodeProp = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 26001

android {
    namespace = "com.swupdater"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swupdater"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeProp
        versionName = versionNameProp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ========== 签名配置 ==========
    // 优先从环境变量读取（CI），回退到 local.properties（本地开发）
    val keystorePath = System.getenv("KEYSTORE_PATH")
        ?: project.findProperty("KEYSTORE_PATH") as? String ?: ""
    val keystorePwd = System.getenv("KEYSTORE_PASSWORD")
        ?: project.findProperty("KEYSTORE_PASSWORD") as? String ?: ""
    val keyAliasVal = System.getenv("KEY_ALIAS")
        ?: project.findProperty("KEY_ALIAS") as? String ?: ""
    val keyPwd = System.getenv("KEY_PASSWORD")
        ?: project.findProperty("KEY_PASSWORD") as? String ?: ""

    signingConfigs {
        if (keystorePath.isNotEmpty() && keystorePwd.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = keyAliasVal
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有签名配置时使用，否则用 debug 签名
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // lint 配置
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // 打包配置：排除冲突文件
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/*.LICENSE.txt",
                "META-INF/*.md",
                "META-INF/*.txt",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/io.netty.versions.properties",
                "META-INF/services/*",
                "OSGI-INF/*"
            )
        }
        jniLibs {
            excludes += listOf("**/libnetty*.so")
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp + Jsoup (网页解析)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // 游戏配置抓取功能依赖
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")
    implementation("org.msgpack:msgpack-core:0.9.8")
    // 使用必要的 Netty 模块而非全部引入
    implementation("io.netty:netty-buffer:4.1.108.Final")
    implementation("io.netty:netty-codec:4.1.108.Final")
    implementation("io.netty:netty-codec-http:4.1.108.Final")
    implementation("io.netty:netty-codec-http2:4.1.108.Final")
    implementation("io.netty:netty-codec-socks:4.1.108.Final")
    implementation("io.netty:netty-common:4.1.108.Final")
    implementation("io.netty:netty-handler:4.1.108.Final")
    implementation("io.netty:netty-resolver:4.1.108.Final")
    implementation("io.netty:netty-transport:4.1.108.Final")
}
