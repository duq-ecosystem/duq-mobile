import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Compose Multiplatform UI (версии управляет compose-mp плагин — надёжно)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // Serialization / coroutines
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // DI (Koin) — выверенная версия 4.1.1
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            // Network (Ktor 3.5.0)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
            // Storage (multiplatform-settings 1.2.0) — KMP-хранилище настроек
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            // NOTE: navigation/lifecycle/haze — на фазах UI.
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
            // NOTE: media3, sherpa-onnx, silero-vad, camerax, play-location, work, security —
            // на фазе audio/platform (expect/actual).
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.duq.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        // NOTE: on-device STT (whisper.cpp JNI + CMake) подключается на фазе audio,
        // когда cpp/ переедет из референс-модуля app/ в src/androidMain/cpp.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // sherpa-onnx ships onnxruntime 1.24.x; Silero VAD ships its own — keep newest.
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.duq.shared.resources"
    generateResClass = always
}
