package uz.mahalla.data.prefs

import uz.mahalla.core.locale.AppLanguage

/** Режим темы. Основная тема тёмная, но выбор пользователя важнее (ТЗ §1). */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val Default: ThemeMode = SYSTEM

        fun fromStoredValue(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: Default
    }
}

/**
 * Пользовательские настройки из DataStore (эпик 1.4). Пароли/токены сюда не
 * попадают — сессия лежит отдельно в [SessionStore], PIN — в PinStorage.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.Default,
    val themeMode: ThemeMode = ThemeMode.Default,
    val onboardingCompleted: Boolean = false,
    val pinConfigured: Boolean = false,
    /** Вход по биометрии включён пользователем (эпик 3.5). */
    val biometricEnabled: Boolean = false,
    /** Город, выбранный вручную при отказе от геолокации; `null` — не выбран. */
    val cityId: String? = null,
    /**
     * Адрес бэкенда, введённый пользователем (issue #26); `null` — не вводился,
     * работаем по адресу из сборки. От `null` зависит первый экран приложения.
     */
    val backendBaseUrl: String? = null,
)
