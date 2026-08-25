package uz.mahalla.feature.profile.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject

/**
 * Профиль: пока только переключатели языка и темы — они замыкают эпики
 * 1.4 и 1.5 (сохранение в DataStore + применение per-app language).
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val localeManager: AppLocaleManager,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { loaded ->
                updateState { copy(settings = loaded) }
            }
        }
    }

    override fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.LanguageSelected -> viewModelScope.launch {
                settingsDataStore.setLanguage(event.language)
                if (localeManager.apply(event.language)) {
                    emitEffect(ProfileEffect.RecreateActivity)
                }
            }

            is ProfileEvent.ThemeSelected -> viewModelScope.launch {
                settingsDataStore.setThemeMode(event.mode)
            }
        }
    }
}
