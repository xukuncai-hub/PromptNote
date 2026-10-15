import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名凭据从 local.properties 读取 —— 该文件已在 .gitignore 中，凭据不会进版本库。
// 需要换成正式签名时，在 local.properties 里补上这四项即可，无需改动本文件：
//   RELEASE_STORE_FILE=../promptnote.jks
//   RELEASE_STORE_PASSWORD=...
//   RELEASE_KEY_ALIAS=promptnote
//   RELEASE_KEY_PASSWORD=...
val localProps = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val hasReleaseKeystore = localProps.getProperty("RELEASE_STORE_FILE") != null

android {
    namespace = "com.htmlnotes.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.htmlnotes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.1.0"

        // 应用只提供中英两套文案，剔除 AndroidX / Material 自带的其余语言资源
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩混淆 + 资源压缩，显著减小包体（规则见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配置正式 keystore 时退回 debug 签名，保证 release 包随时可安装调试。
            // 注意：debug 签名的包不能用于正式分发 —— debug key 是公开的，任何人都能伪造更新。
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
