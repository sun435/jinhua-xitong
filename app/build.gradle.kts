plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jinhua.xitong"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jinhua.xitong"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    // 签名钥匙不放在代码里：云端打包时由 GitHub 的缓存提供，保证每次签名一样、可以直接覆盖安装
    val ksPath = System.getenv("JHXT_KEYSTORE")
    val ksPass = System.getenv("JHXT_KEYSTORE_PASS")
    if (ksPath != null && ksPass != null && file(ksPath).exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "jinhua"
                keyPassword = ksPass
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
