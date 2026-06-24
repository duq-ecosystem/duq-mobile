import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.duq.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
                ?: project.findProperty("KEYSTORE_FILE")?.toString()
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
            val kAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS")?.toString()
            val kPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD")?.toString()
            if (keystoreFile != null && keystorePassword != null && kAlias != null && kPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = kAlias
                keyPassword = kPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.duq.android"   // сохранён — та же подпись/автообновление
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = (System.getenv("DUQ_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 400
        versionName = "1.0.${(System.getenv("DUQ_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "400")}"
        ndk { abiFilters += "arm64-v8a" }

        val serverToken = System.getenv("SERVER_TOKEN") ?: localProperties.getProperty("SERVER_TOKEN", "")
        buildConfigField("String", "SERVER_TOKEN", "\"$serverToken\"")
        val ghReleaseToken = System.getenv("GH_RELEASE_TOKEN") ?: localProperties.getProperty("GH_RELEASE_TOKEN", "")
        buildConfigField("String", "GH_RELEASE_TOKEN", "\"$ghReleaseToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null } ?: signingConfigs.getByName("debug")
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

    // lintVitalRelease крашит IncompatibleClassChangeError в NonNullableMutableLiveDataDetector
    // (баг AGP-lint детектора под Kotlin 2.2) — не блокируем сборку APK линтом.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { pickFirsts += "**/libonnxruntime.so" }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    // koin-android добавляется на фазе DI (init Koin в DuqApplication).
}
