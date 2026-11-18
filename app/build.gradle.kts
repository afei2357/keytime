plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.keytime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.keytime"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "3.7.0"
    }

    buildFeatures {
        viewBinding = true
    }

    // 签名信息不写入版本库：由本地环境变量提供，未配置时仍可直接构建（使用默认调试签名）。
    // 需要的变量：KEYTIME_KEYSTORE（相对项目根或绝对路径）、KEYTIME_STORE_PASSWORD、
    //            KEYTIME_KEY_ALIAS、KEYTIME_KEY_PASSWORD。
    val releaseKeystore = System.getenv("KEYTIME_KEYSTORE")?.takeIf { it.isNotBlank() }
    val releaseAlias = System.getenv("KEYTIME_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "keytime"
    val releaseStorePassword = System.getenv("KEYTIME_STORE_PASSWORD").orEmpty()
    val releaseKeyPassword = System.getenv("KEYTIME_KEY_PASSWORD").orEmpty()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // 若配置了正式签名，debug 变体也复用它，便于 adb install -r 覆盖安装；
        // 未配置时两个变体都能正常构建，只是使用默认调试签名。
        val signing = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        debug {
            if (signing != null) signingConfig = signing
        }
        release {
            if (signing != null) signingConfig = signing
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}