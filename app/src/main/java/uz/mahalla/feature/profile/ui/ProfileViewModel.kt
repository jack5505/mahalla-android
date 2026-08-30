package uz.mahalla.feature.profile.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.profile.data.SessionsRepository
import uz.mahalla.feature.profile.domain.DeviceSession
import javax.inject.Inject

/**
 * Профиль: кто вошёл, настройки приложения, устройства с открытым входом и
 * выход из аккаунта (issue #61).
 *
 * Данные профиля читаются из [UserProfileStore] — их записал вход. Отдельного
 * `GET /users/me` у бэкенда нет, поэтому обновить имя или аватар отсюда
 * нельзя: появится эндпоинт — появится и экран редактирования.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val localeManager: AppLocaleManager,
    private val httpInspector: HttpInspector,
    private val userProfileStore: UserProfileStore,
    private val sessionsRepository: SessionsRepository,
    private val authRepository: AuthRepository,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {

    init {
        updateState { copy(httpInspectorAvailable = httpInspector.isAvailable) }
        viewModelScope.launch {
            settingsDataStore.settings.collect { loaded ->
                updateState { copy(settings = loaded) }
            }
        }
        viewModelScope.launch {
            userProfileStore.profile.collect { loaded ->
                updateState { copy(profile = loaded) }
            }
        }
        loadSessions()
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

            // Интента может не быть (сборка без инспектора) — тогда и строки в
            // профиле нет, но событие из старого состояния экрана прилететь
            // может: молча ничего не делаем, а не падаем на startActivity(null).
            ProfileEvent.HttpInspectorRequested -> httpInspector.launchIntent()?.let { intent ->
                emitEffect(ProfileEffect.OpenHttpInspector(intent))
            }

            // Возврат на экран: вход с другого устройства мог случиться, пока
            // приложение было в фоне. Пока список грузится или идёт запрос по
            // строке, перезапрашивать нечего — иначе ответ приедет на уже
            // сменившееся состояние.
            ProfileEvent.ScreenResumed ->
                if (currentState.pendingSessionId == null && !currentState.sessions.isLoading) {
                    loadSessions(showLoading = false)
                }

            ProfileEvent.SessionsRetryRequested -> loadSessions()

            ProfileEvent.LogoutRequested -> updateState { copy(confirmLogout = true) }

            ProfileEvent.LogoutDismissed -> updateState { copy(confirmLogout = false) }

            ProfileEvent.LogoutConfirmed -> logout()

            is ProfileEvent.SessionRevokeRequested ->
                // Своё устройство из списка не отзывается: это был бы выход,
                // о котором экран не сказал ни слова.
                if (!event.session.isCurrent) {
                    updateState { copy(confirmRevoke = event.session, sessionFailure = null) }
                }

            ProfileEvent.SessionRevokeDismissed -> updateState { copy(confirmRevoke = null) }

            ProfileEvent.SessionRevokeConfirmed ->
                currentState.confirmRevoke?.let { session -> revoke(session) }

            is ProfileEvent.SessionTrustToggled -> setTrusted(event.session, event.trusted)
        }
    }

    /**
     * @param showLoading скелетон вместо списка. При обновлении поверх уже
     * показанных устройств он не нужен: список бы мигал на каждом возврате.
     */
    private fun loadSessions(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) updateState { copy(sessions = ScreenState.Loading) }
            val result = sessionsRepository.sessions()
            updateState { copy(sessions = result.toListScreenState()) }
        }
    }

    private fun logout() {
        if (currentState.loggingOut) return
        updateState { copy(confirmLogout = false, loggingOut = true) }
        viewModelScope.launch {
            authRepository.logout()
            // Онбординг начинается заново: сессии больше нет, и следующий
            // запуск обязан привести на экран входа, а не в main, где каждый
            // запрос ответит 401. Отказ записи не повод оставить человека в
            // приложении — выход уже случился локально.
            runCatchingCancellable { settingsDataStore.setOnboardingCompleted(false) }
            updateState { copy(loggingOut = false) }
            emitEffect(ProfileEffect.LoggedOut)
        }
    }

    private fun revoke(session: DeviceSession) {
        if (currentState.pendingSessionId != null) return
        updateState {
            copy(confirmRevoke = null, pendingSessionId = session.id, sessionFailure = null)
        }
        viewModelScope.launch {
            when (val result = sessionsRepository.revoke(session.id)) {
                is ApiResult.Success -> {
                    updateState { copy(pendingSessionId = null) }
                    // Перечитываем у сервера, а не вычёркиваем строку сами:
                    // сессия могла быть не одна, а список — устареть.
                    loadSessions(showLoading = false)
                }

                is ApiResult.Failure -> updateState {
                    copy(pendingSessionId = null, sessionFailure = result.failure)
                }
            }
        }
    }

    private fun setTrusted(session: DeviceSession, trusted: Boolean) {
        if (currentState.pendingSessionId != null) return
        updateState { copy(pendingSessionId = session.id, sessionFailure = null) }
        viewModelScope.launch {
            when (val result = sessionsRepository.setTrusted(session.id, trusted)) {
                is ApiResult.Success -> {
                    updateState { copy(pendingSessionId = null) }
                    loadSessions(showLoading = false)
                }

                is ApiResult.Failure -> updateState {
                    copy(pendingSessionId = null, sessionFailure = result.failure)
                }
            }
        }
    }
}
