package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState

data class WelcomeState(
    val language: AppLanguage = AppLanguage.Default,
) : UiState

sealed interface WelcomeEvent : UiEvent {
    data class LanguageSelected(val language: AppLanguage) : WelcomeEvent
}

sealed interface WelcomeEffect : UiEffect {
    /** До API 33 смену языка применяет только пересоздание Activity. */
    data object RecreateActivity : WelcomeEffect
}
