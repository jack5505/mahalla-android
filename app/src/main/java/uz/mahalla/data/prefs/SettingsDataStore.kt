package uz.mahalla.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uz.mahalla.core.locale.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки приложения в DataStore (эпик 1.4): язык, тема, флаг онбординга,
 * признак настроенного PIN.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<AppSettings> = dataStore.data
        .map { preferences ->
            AppSettings(
                language = AppLanguage.fromTag(preferences[PreferenceKeys.Language]),
                themeMode = ThemeMode.fromStoredValue(preferences[PreferenceKeys.ThemeMode]),
                onboardingCompleted = preferences[PreferenceKeys.OnboardingCompleted] ?: false,
                // Ровно то же условие, что в PinStorage.isConfigured: без соли
                // хэш проверить нечем, значит PIN не настроен.
                pinConfigured = preferences[PreferenceKeys.PinHash] != null &&
                    preferences[PreferenceKeys.PinSalt] != null,
                biometricEnabled = preferences[PreferenceKeys.BiometricEnabled] ?: false,
                cityId = preferences[PreferenceKeys.CityId],
                backendBaseUrl = preferences[PreferenceKeys.BackendBaseUrl],
                backendCertificatePin = preferences[PreferenceKeys.BackendCertificatePin],
            )
        }
        // Файл настроек может быть недоступен (нет места, права, IO-ошибка).
        // Настройки — не критичные данные: отдаём значения по умолчанию, а не
        // роняем приложение на старте (состояние корня читает этот flow).
        .catch { emit(AppSettings()) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[PreferenceKeys.Language] = language.storedValue }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferenceKeys.ThemeMode] = mode.name }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[PreferenceKeys.OnboardingCompleted] = completed }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.BiometricEnabled] = enabled }
    }

    suspend fun setCityId(cityId: String) {
        dataStore.edit { it[PreferenceKeys.CityId] = cityId }
    }

    /** Адрес уже нормализован (`BackendUrl.normalize`) — здесь только запись. */
    suspend fun setBackendBaseUrl(baseUrl: String) {
        dataStore.edit { it[PreferenceKeys.BackendBaseUrl] = baseUrl }
    }

    /** Отпечаток уже нормализован (`CertificateFingerprint.of`) — issue #32. */
    suspend fun setBackendCertificatePin(fingerprint: String) {
        dataStore.edit { it[PreferenceKeys.BackendCertificatePin] = fingerprint }
    }
}
