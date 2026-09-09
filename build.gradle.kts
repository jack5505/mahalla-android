// Версии плагинов едины для всех модулей и живут в gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Push (эпик 11). Здесь плагин только попадает на classpath сборки —
    // применяется он в `:app` и только при наличии `google-services.json`
    // (файл в .gitignore, см. комментарий там же).
    alias(libs.plugins.google.services) apply false
}
