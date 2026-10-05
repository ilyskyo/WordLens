plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.io.FileInputStream
import java.util.Properties

// Release signing is opt-in and read from local.properties (git-ignored).
// Deliberately NOT falling back to the debug key: a silent debug-signed "release" build is
// the classic way to ship an artifact nobody can update. If the four fields are absent the
// release variant simply stays unsigned and we say so loudly.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
val releaseStore = keystoreProperties.getProperty("release.storeFile")
val hasReleaseSigning = listOf(
    releaseStore,
    keystoreProperties.getProperty("release.storePassword"),
    keystoreProperties.getProperty("release.keyAlias"),
    keystoreProperties.getProperty("release.keyPassword"),
).all { !it.isNullOrEmpty() }

android {
    namespace = "com.ilyskyo.wordlens"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ilyskyo.wordlens"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // zh / en / ja / ko UI strings ship in the app.
        resourceConfigurations += listOf("zh", "en", "ja", "ko")

        // ARM only. MediaPipe and ML Kit each ship ~10-13 MB of native code per ABI, so keeping
        // x86/x86_64 would add ~52 MB of APK for emulators that are not a target device.
        // Anyone who needs an emulator build can remove these two lines locally.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = keystoreProperties.getProperty("release.storePassword", "")
                keyAlias = keystoreProperties.getProperty("release.keyAlias", "")
                keyPassword = keystoreProperties.getProperty("release.keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // .tflite weights are read straight from assets at startup; deflating them only adds
    // inflate latency for no meaningful size win (they are quantized weights already).
    androidResources {
        noCompress += listOf("tflite")
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                // 可变字体的 FontVariation.Settings 目前仍标注实验性。
                // 变体字体省掉了整套静态字重文件，是刻意取舍，不值得为此退回多文件方案。
                "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    // 刻意不引入 material-icons-extended：两千多个图标会给 dex 带来可观体积，
    // 而本应用只用到 7 个，已在 ui/icons/WordLensIcons.kt 里手绘。
    // app/src/main/res/font 下的两款字体同样以本地资源方式携带。

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // On-device image labelling: bundled model, no Play Services download, works offline.
    implementation(libs.mlkit.image.labeling)
    // Optional, only used when Google Play services is present (better multi-subject cuts).
    implementation(libs.mlkit.subject.segmentation)
    // MediaPipe Tasks Vision: InteractiveSegmenter (magic_touch) drives tap-to-cut-out.
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
