package uz.mahalla.feature.profile.ui

import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.ThemeMode

data class ProfileState(
    val settings: AppSettings = AppSettings(),
) : UiState

sealed interface ProfileEvent : UiEvent {
    data class LanguageSelected(val language: AppLanguage) : ProfileEvent
    data class ThemeSelected(val mode: ThemeMode) : ProfileEvent
}

sealed interface ProfileEffect : UiEffect {
    /** До API 33 смену языка применяет только пересоздание Activity. */
    data object RecreateActivity : ProfileEffect
}
