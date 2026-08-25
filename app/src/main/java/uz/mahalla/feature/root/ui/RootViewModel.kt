package uz.mahalla.feature.root.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import javax.inject.Inject

/**
 * Состояние корня: пока настройки не прочитаны из DataStore, показывать UI
 * нельзя — иначе мигнёт неправильная тема и неправильный стартовый экран.
 * На это время держится системный splash (эпик 1.6).
 */
sealed interface RootUiState {
    data object Loading : RootUiState
    data class Ready(val settings: AppSettings) : RootUiState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsDataStore: SettingsDataStore,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    val state: StateFlow<RootUiState> = settingsDataStore.settings
        .map<AppSettings, RootUiState> { RootUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RootUiState.Loading,
        )

    fun onOnboardingFinished() {
        viewModelScope.launch { onboardingRepository.markCompleted() }
    }
}
