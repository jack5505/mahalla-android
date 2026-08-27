package uz.mahalla.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Ключи DataStore в одном месте (эпик 1.4): их легко пересматривать при
 * миграциях и невозможно случайно объявить дважды с разным типом.
 */
internal object PreferenceKeys {
    val Language = stringPreferencesKey("settings_language")
    val ThemeMode = stringPreferencesKey("settings_theme_mode")
    val OnboardingCompleted = booleanPreferencesKey("settings_onboarding_completed")
    val BiometricEnabled = booleanPreferencesKey("settings_biometric_enabled")

    /** Город выбирается вручную, когда пользователь отказал в геолокации (эпик 3.6). */
    val CityId = stringPreferencesKey("settings_city_id")

    /** Адрес бэкенда, введённый пользователем на первом экране (issue #26). */
    val BackendBaseUrl = stringPreferencesKey("settings_backend_base_url")

    /** Отпечаток сертификата сервера, подтверждённый пользователем (issue #32). */
    val BackendCertificatePin = stringPreferencesKey("settings_backend_certificate_pin")

    val SessionAccessToken = stringPreferencesKey("session_access_token")
    val SessionRefreshToken = stringPreferencesKey("session_refresh_token")
    val SessionExpiresAt = longPreferencesKey("session_expires_at")

    val PinHash = stringPreferencesKey("pin_hash_encrypted")
    val PinSalt = stringPreferencesKey("pin_salt")

    /** История поиска (эпик 4.3) — одна строка, порядок значим. */
    val SearchHistory = stringPreferencesKey("discovery_search_history")
}
