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

    /** Отказ записи: DataStore кидает `IOException` при нехватке места. */
    var writeFailure: Exception? = null

    override val settings: Flow<AppSettings> = state

    override val completed: Flow<Boolean> = state.map { it.onboardingCompleted }

    val current: AppSettings get() = state.value

    override suspend fun setLanguage(language: AppLanguage) {
        writeFailure?.let { throw it }
        state.update { it.copy(language = language) }
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        writeFailure?.let { throw it }
        biometricWrites += enabled
        state.update { it.copy(biometricEnabled = enabled) }
    }

    override suspend fun setCity(cityId: String) {
        writeFailure?.let { throw it }
        state.update { it.copy(cityId = cityId) }
    }

    override suspend fun markCompleted() {
        writeFailure?.let { throw it }
        state.update { it.copy(onboardingCompleted = true) }
    }

    override suspend fun clearCompleted() {
        writeFailure?.let { throw it }
        state.update { it.copy(onboardingCompleted = false) }
    }
}
