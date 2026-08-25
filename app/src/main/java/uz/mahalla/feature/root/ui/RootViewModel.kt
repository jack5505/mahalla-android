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

    /**
     * @param startWithOnboarding стартовый пункт графа навигации. Зафиксирован
     * на первой эмиссии настроек и дальше не меняется — см. [RootViewModel].
     */
    data class Ready(
        val settings: AppSettings,
        val startWithOnboarding: Boolean,
    ) : RootUiState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsDataStore: SettingsDataStore,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    /**
     * Стартовый пункт графа решается один раз за жизнь процесса.
     *
     * `NavHost` пересобирает граф при смене `startDestination` и сбрасывает
     * back stack, а настройки — живой flow: смена темы/языка в профиле или
     * запись флага онбординга обнуляли бы навигацию. Уход из онбординга — это
     * `navigate(MainGraph) { popUpTo(...) }` в графе, флаг в DataStore нужен
     * только следующему запуску.
     *
     * Поле безопасно: `stateIn` держит одну подписку на upstream, то есть
     * `map` ниже исполняется в одной корутине.
     */
    private var startWithOnboarding: Boolean? = null

    val state: StateFlow<RootUiState> = settingsDataStore.settings
        .map<AppSettings, RootUiState> { settings ->
            val start = startWithOnboarding ?: !settings.onboardingCompleted
            startWithOnboarding = start
            RootUiState.Ready(settings = settings, startWithOnboarding = start)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RootUiState.Loading,
        )

    fun onOnboardingFinished() {
        viewModelScope.launch { onboardingRepository.markCompleted() }
    }
}
