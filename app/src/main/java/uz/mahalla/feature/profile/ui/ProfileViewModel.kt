package uz.mahalla.feature.profile.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.crash.reportSwallowed
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.media.data.MediaRepository
import uz.mahalla.feature.profile.data.SessionsRepository
import uz.mahalla.feature.profile.domain.DeviceSession

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
    private val mediaRepository: MediaRepository,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {

    /** Загрузка фото: держим job, потому что её можно отменить (issue #101). */
    private var avatarJob: Job? = null

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

            is ProfileEvent.AvatarPicked -> uploadAvatar(event.source)

            ProfileEvent.AvatarUploadCancelled -> cancelAvatarUpload()
        }
    }

    /**
     * Фото профиля (issue #101): сжать, отправить, запомнить адрес.
     *
     * **Адрес сохраняется только локально**, и это не выбор клиента: у бэкенда
     * нет ни `PUT /users/me`, ни другой ручки, которой можно сообщить аватар
     * (`UserInfo.avatarUrl` он отдаёт, но принимать не умеет — проверено по
     * полной схеме). Значит после следующего входа профиль перезапишется
     * ответом сервера, и адрес пропадёт вместе с ним. Сам файл при этом
     * остаётся на сервере и числится за загрузившим (`ownerId`).
     *
     * `entityId` — id пользователя, когда он известен: по нему загруженное
     * потом находится (`GET media/entity/{id}`). `entityType` не отправляется:
     * словаря его значений в схеме нет, а выдуманное значение бэкенд запомнит,
     * и разбирать это придётся руками.
     */
    private fun uploadAvatar(source: String) {
        if (currentState.avatarUpload.inProgress) return
        updateState { copy(avatarUpload = AvatarUpload(inProgress = true)) }
        avatarJob = viewModelScope.launch {
            val result = mediaRepository.uploadImage(
                source = source,
                entityId = currentState.profile.id?.takeIf { it.isNotBlank() },
                // Прогресс приезжает с потока OkHttp; updateState на
                // MutableStateFlow потокобезопасен.
                onProgress = { percent ->
                    updateState { copy(avatarUpload = avatarUpload.copy(percent = percent)) }
                },
            )
            when (result) {
                is ApiResult.Success -> {
                    saveAvatar(result.data.url)
                    updateState { copy(avatarUpload = AvatarUpload()) }
                }

                is ApiResult.Failure -> updateState {
                    copy(avatarUpload = AvatarUpload(failure = result.failure))
                }
            }
            avatarJob = null
        }
    }

    /**
     * Профиль перечитывается перед записью, а не берётся из состояния экрана:
     * `save` пишет все поля разом, и запись по устаревшему снимку стёрла бы
     * имя или номер, приехавшие, пока шла загрузка.
     *
     * Отказ хранилища не отменяет загрузку: файл на сервере уже лежит, а
     * молчаливая ошибка записи уходит в отчёты (issue #74).
     */
    private suspend fun saveAvatar(url: String) {
        runCatchingCancellable {
            val stored: UserProfile = userProfileStore.current()
            userProfileStore.save(stored.copy(avatarUrl = url))
        }.reportSwallowed("profile.saveAvatar")
    }

    /**
     * Отмена: корутина снимается, Retrofit обрывает вызов, недописанное тело
     * до сервера не доезжает. Состояние сбрасывается здесь — отменённая
     * корутина до своего `when` уже не дойдёт.
     */
    private fun cancelAvatarUpload() {
        avatarJob?.cancel()
        avatarJob = null
        updateState { copy(avatarUpload = AvatarUpload()) }
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
                .reportSwallowed("settings.setOnboardingCompleted")
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
