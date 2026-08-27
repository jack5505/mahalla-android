package uz.mahalla.feature.profile.ui

import android.content.Intent
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.ThemeMode

/**
 * @param httpInspectorAvailable в сборке есть инспектор трафика (issue #30) —
 * показываем строку «сетевые запросы». В release её нет.
 */
data class ProfileState(
    val settings: AppSettings = AppSettings(),
    val httpInspectorAvailable: Boolean = false,
) : UiState

sealed interface ProfileEvent : UiEvent {
    data class LanguageSelected(val language: AppLanguage) : ProfileEvent
    data class ThemeSelected(val mode: ThemeMode) : ProfileEvent
    data object HttpInspectorRequested : ProfileEvent
}

sealed interface ProfileEffect : UiEffect {
    /** До API 33 смену языка применяет только пересоздание Activity. */
    data object RecreateActivity : ProfileEffect

    /** Экран инспектора трафика: интент отдаёт сама библиотека (issue #30). */
    data class OpenHttpInspector(val intent: Intent) : ProfileEffect
}
