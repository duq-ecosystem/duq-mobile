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
            // static: gradle linkReleaseFramework и compileKotlinIosArm64 проходят зелёными;
            // падает только xcodebuild archive (Undefined symbols — нужны системные фреймворки
            // в OTHER_LDFLAGS pbxproj). isStatic=false давал Undefined symbols РАНЬШЕ — на самой
            // gradle-линковке динамика (хуже). Доведение .ipa — отдельная итерация на macOS-CI.
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
            implementation(libs.kotlinx.datetime)  // dateLabel бесед (мультиплатформенно, вместо java.time)
            // DI (Koin) — выверенная версия 4.1.1
            implementation(libs.koin.core)
            // koin-core-viewmodel: DSL viewModel{}/viewModelOf в общем графе (viewModelModule).
            implementation(libs.koin.core.viewmodel)
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
            // ViewModel + navigation (multiplatform, выверено по офиц. KMP doc)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            // koinViewModel<...>() в экранах (фаза экранов) — мультиплатформенный мост Koin↔ViewModel.
            implementation(libs.koin.compose.viewmodel)
            // haze (frosted-glass блюр) — multiplatform 1.6; пузыри чата размывают утку-watermark.
            implementation(libs.haze)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
            // Audio (Android actual): плеер, on-device TTS/VAD, распаковка модели
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.sherpa.onnx)
            implementation(libs.silero.vad)
            implementation(libs.commons.compress)
            implementation(libs.okhttp)   // WhisperLocal/TtsLocal: докачка моделей + DoH-резолвер для Ktor OkHttp-движка
            implementation(libs.okhttp.dnsoverhttps)  // DoH fallback (обход «Unable to resolve host») в OkHttp-движке Ktor
            // phone-control (bot→phone native commands): camera.snap (CameraX headless ImageCapture),
            // location.get (FusedLocation), screen.record (MediaProjection из platform SDK — без доп. dep),
            // lifecycle-runtime для одноразового LifecycleRegistry capture-владельца.
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.play.services.location)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.duq.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // NDK запиннен явно: без этого раннер CI брал случайную предустановленную версию,
    // и одна из них оказалась битой (source.properties отсутствовал → CXX1101, сборка падала).
    // r26d — стабильная LTS; CI ставит ровно её (см. android.yml, шаг Install NDK).
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }

        // On-device STT: собираем libduqwhisper.so (JNI-мост + статика whisper.cpp).
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Путь к CMake-скрипту JNI-моста whisper.cpp (libduqwhisper.so).
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
