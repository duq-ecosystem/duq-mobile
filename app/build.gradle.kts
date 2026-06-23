import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("jacoco")
}

// Load local.properties for secret keys
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "com.duq.android"
    compileSdk = 35  // 35 нужен Haze 1.6 / Compose 1.8 (targetSdk остаётся 34)

    // Release signing configuration
    signingConfigs {
        create("release") {
            // Read from environment variables or local.properties
            val keystoreFile = System.getenv("KEYSTORE_FILE")
                ?: project.findProperty("KEYSTORE_FILE")?.toString()
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
            val keyAlias = System.getenv("KEY_ALIAS")
                ?: project.findProperty("KEY_ALIAS")?.toString()
            val keyPassword = System.getenv("KEY_PASSWORD")
                ?: project.findProperty("KEY_PASSWORD")?.toString()

            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.duq.android"
        minSdk = 26  // software Ed25519 (BouncyCastle) — no AndroidKeyStore dependency
        targetSdk = 34
        // versionCode auto-increments from the CI run number so every published
        // build is strictly newer — the in-app updater + channel sync rely on it.
        // Local builds fall back to a fixed base.
        versionCode = (System.getenv("DUQ_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 6
        versionName = "1.0.${(System.getenv("DUQ_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "6")}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Ship only arm64-v8a — cuts APK from ~98MB to ~25MB (drops x86/x86_64 emulator libs)
        ndk {
            abiFilters += "arm64-v8a"
        }

        // On-device STT: собираем libduqwhisper.so (JNI-мост + статика whisper.cpp).
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }

        // API Configuration (can be overridden per flavor/buildType)
        buildConfigField("String", "API_BASE_URL", "\"https://on-za-menya.online\"")

        // Porcupine API key (from local.properties, not committed to git)
        // Env first (CI passes it as PORCUPINE_API_KEY), then local.properties for
        // local builds. Reading only local.properties shipped EVERY CI release with
        // an EMPTY key, so wake word was silently disabled ("API key not set").
        val porcupineKey = System.getenv("PORCUPINE_API_KEY")
            ?: localProperties.getProperty("PORCUPINE_API_KEY", "")
        buildConfigField("String", "PORCUPINE_API_KEY", "\"$porcupineKey\"")

        // Read-only GitHub token (contents:read on this private repo) for self-update.
        // CI passes GH_RELEASE_TOKEN secret; local builds read local.properties. Empty
        // → AppUpdater disables itself (no crash). Baked into BuildConfig, not committed.
        val ghReleaseToken = System.getenv("GH_RELEASE_TOKEN")
            ?: localProperties.getProperty("GH_RELEASE_TOKEN", "")
        buildConfigField("String", "GH_RELEASE_TOKEN", "\"$ghReleaseToken\"")

        // Edge-токен периметра: nginx проверяет его (X-Auth-Token) на ВСЕХ серверных
        // эндпоинтах, без него → 401 → fail2ban банит IP. = vault-sync token (тот же,
        // что шлёт Obsidian-плагин). CI передаёт секрет SERVER_TOKEN; локальная сборка
        // читает local.properties. Пусто → заголовок не шлётся (отладка до гейта).
        val serverToken = System.getenv("SERVER_TOKEN")
            ?: localProperties.getProperty("SERVER_TOKEN", "")
        buildConfigField("String", "SERVER_TOKEN", "\"$serverToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing config if available
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }

    // Путь к CMake-скрипту JNI-моста whisper.cpp (libduqwhisper.so).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle (bcprov) ships a multi-release OSGI manifest that
            // collides during resource merge — drop the duplicate.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.extensions.configure<JacocoTaskExtension> {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/Hilt_*.*",
        "**/*_Factory.*",
        "**/*_MembersInjector.*"
    )

    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("jacoco/testDebugUnitTest.exec")
    })
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Frosted-glass backdrop blur (утка на фоне размывается под пузырями сообщений)
    implementation("dev.chrisbanes.haze:haze:1.6.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Porcupine Wake Word (v4 for custom wake word compatibility)
    implementation("ai.picovoice:porcupine-android:4.0.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Audio playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")

    // On-device TTS: sherpa-onnx (k2-fsa, Apache-2.0) + Piper VITS RU. AAR через JitPack
    // (com.github.k2-fsa:sherpa-onnx) — официальный канал распространения Android-биндинга.
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.3")
    // Распаковка скачанного бандла модели (.tar.bz2). Apache Commons Compress (Apache-2.0) —
    // индустриальный стандарт; берём ради BZip2 + Tar (Android SDK их не декодирует).
    implementation("org.apache.commons:commons-compress:1.27.1")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager — periodic background self-update check
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Security - Encrypted SharedPreferences for token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // BouncyCastle - software Ed25519 device identity (matches OpenClaw gateway)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Voice Activity Detection (Silero VAD - DNN based, more accurate)
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.56")
    ksp("com.google.dagger:hilt-compiler:2.56")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // ML Kit barcode scanning (QR)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
