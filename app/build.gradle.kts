import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key is read from keystore.properties (never commit it).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile", "").isNullOrBlank()

android {
    namespace = "com.nya.dstranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nya.dstranslate"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "1.6.27"

        // ML Kit ships native libs for every ABI; keep ARM only to halve the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 16 KB page size: нативные библиотеки должны лежать несжатыми
        // и выровненными, иначе APK не поставится на устройства с 16 КБ страницами.
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Диалоговая тема AppCompat для окна «Перевести» из меню выделения:
    // нужен именно Theme.AppCompat.Dialog (плавающее окно + затемнение),
    // собственные Compose-темы этого не дают.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Распознавание речи: sherpa-onnx 1.13.8 (официальный AAR из GitHub
    // Releases — в Maven Central его нет). Внутри готовые .so и Kotlin-API;
    // используется потоковым движком «Авто» и моно-режимов.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // On-device OCR: model is bundled into the APK, no internet needed.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}