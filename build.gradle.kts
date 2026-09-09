// Версии плагинов едины для всех модулей и живут в gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
    // com.android.test приезжает тем же артефактом AGP, что и
    // com.android.application: объявить версию можно только здесь, иначе
    // Gradle ругается «plugin is already on the classpath with an unknown
    // version» при первом же применении в модуле.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
