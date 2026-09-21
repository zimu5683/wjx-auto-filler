import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProps = Properties().apply {
    File(projectDir.parentFile, "version.properties").inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName", "0.0.0")
val appVersionCode: Int = versionProps.getProperty("versionCode", "1").toInt()

android {
    namespace = "com.wjx.autofill"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wjx.autofill"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 本工程没有任何 native 代码（APK 内不含 .so），因此刻意 **不设置 ndk.abiFilters**：
        // 产出通用包，arm64-v8a / armeabi-v7a / x86 / x86_64 的真机与模拟器都能安装。
        // 这是「换一台全新设备也能装」的结构性保证，改动此处会破坏该保证。
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storePath = providers.environmentVariable("WJX_KEYSTORE_FILE").orNull
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = project.file(storePath)
                storePassword = providers.environmentVariable("WJX_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("WJX_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("WJX_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 正式分发必须配好 WJX_KEYSTORE_* 环境变量；缺失时回退 debug 签名（仅供本地调试，
            // 回退签名的包无法覆盖安装正式包）。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
        viewBinding = true
    }

    lint {
        // NewApi 等为 error：静态保证 minSdk 24 上不会调用更高版本 API。
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 二维码解码：纯 Java，无 GMS 依赖，同一套解码器同时服务「相机实时扫码」与「相册图片」两条路径。
    implementation("com.google.zxing:core:3.5.3")

    // 相机：AndroidX 官方维护，新机型/新系统兼容性优于已停更的三方扫码库。
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
