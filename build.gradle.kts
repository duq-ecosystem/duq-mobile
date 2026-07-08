plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
}

// Detekt — статический анализ Kotlin (SAST/lint) для всех модулей (shared, androidApp).
// Конфиг: config/detekt/detekt.yml, baseline: config/detekt/baseline.xml (известный
// легаси-долг зафиксирован, gate ловит НОВЫЕ проблемы — тот же ratchet, что у ядра).
// Прогон: ./gradlew detekt (в CI, не локально — беречь ноут).
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        val bl = rootProject.file("config/detekt/baseline.xml")
        if (bl.exists()) baseline = bl
        // KMP: анализируем все kotlin-исходники модуля (common/android/ios source sets).
        source.setFrom(
            "src/commonMain/kotlin",
            "src/androidMain/kotlin",
            "src/iosMain/kotlin",
            "src/main/kotlin",
        )
    }

    dependencies {
        add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
    }
}
