package uz.mahalla.feature.onboarding.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Флаг «онбординг пройден» (эпик 1.4). От него зависит стартовый граф
 * навигации, поэтому он живёт в DataStore, а не в памяти.
 */
@Singleton
class OnboardingRepository @Inject constructor(
    private val settings: SettingsDataStore,
) {
    val completed: Flow<Boolean> = settings.settings.map { it.onboardingCompleted }

    suspend fun markCompleted() {
        settings.setOnboardingCompleted(true)
    }
}
