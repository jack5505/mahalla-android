package uz.mahalla.feature.onboarding.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки, которые правит онбординг (эпики 1.4 и 3): язык на welcome,
 * флаг биометрии, выбранный вручную город и признак «онбординг пройден».
 *
 * Интерфейс, а не класс: от него зависит стартовый пункт графа навигации и
 * четыре ViewModel онбординга — все они тестируются с фейком, без DataStore и
 * Robolectric.
 */
interface OnboardingRepository {

    val settings: Flow<AppSettings>

    /** От флага зависит стартовый граф навигации, поэтому он в DataStore. */
    val completed: Flow<Boolean>

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setBiometricEnabled(enabled: Boolean)

    suspend fun setCity(cityId: String)

    suspend fun markCompleted()
}

@Singleton
class DataStoreOnboardingRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : OnboardingRepository {

    override val settings: Flow<AppSettings> = settingsDataStore.settings

    override val completed: Flow<Boolean> = settingsDataStore.settings.map { it.onboardingCompleted }

    override suspend fun setLanguage(language: AppLanguage) {
        settingsDataStore.setLanguage(language)
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        settingsDataStore.setBiometricEnabled(enabled)
    }

    override suspend fun setCity(cityId: String) {
        settingsDataStore.setCityId(cityId)
    }

    override suspend fun markCompleted() {
        settingsDataStore.setOnboardingCompleted(true)
    }
}
