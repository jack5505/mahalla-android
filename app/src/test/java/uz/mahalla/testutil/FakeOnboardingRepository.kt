package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.feature.onboarding.data.OnboardingRepository

/** Настройки онбординга в памяти — без DataStore и Robolectric. */
class FakeOnboardingRepository(
    initial: AppSettings = AppSettings(),
) : OnboardingRepository {

    private val state = MutableStateFlow(initial)

    /** История записей флага биометрии: важен и сам факт записи, и значение. */
    val biometricWrites = mutableListOf<Boolean>()

    override val settings: Flow<AppSettings> = state

    override val completed: Flow<Boolean> = state.map { it.onboardingCompleted }

    val current: AppSettings get() = state.value

    override suspend fun setLanguage(language: AppLanguage) {
        state.update { it.copy(language = language) }
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        biometricWrites += enabled
        state.update { it.copy(biometricEnabled = enabled) }
    }

    override suspend fun setCity(cityId: String) {
        state.update { it.copy(cityId = cityId) }
    }

    override suspend fun markCompleted() {
        state.update { it.copy(onboardingCompleted = true) }
    }
}
