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
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.media.data.MediaRepository
import uz.mahalla.feature.profile.data.ProfileRepository
import uz.mahalla.feature.profile.data.SessionsRepository
import uz.mahalla.feature.profile.domain.DeviceSession

/**
 * Профиль: кто вошёл, настройки приложения, устройства с открытым входом и
 * выход из аккаунта (issue #61).
 *
 * Шапка показывает [UserProfileStore] — его слой, где живёт как ответ входа,
 * так и уже перечитанный `GET users/me` (issue #170): экран не различает
 * источник, а [ProfileRepository] пишет один и тот же профиль в одно
 * хранилище.
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
    private val profileRepository: ProfileRepository,
    private val biometricAvailability: BiometricAvailability,
) : MviViewModel<ProfileState, ProfileEvent, ProfileEffect>(ProfileState()) {

    /** Загрузка фото: держим job, потому что её можно отменить (issue #101). */
    private var avatarJob: Job? = null

    private var sessionsJob: Job? = null

    private var profileRefreshJob: Job? = null

    private var nameSaveJob: Job? = null

    init {
        updateState {
            copy(
                httpInspectorAvailable = httpInspector.isAvailable,
                biometricStatus = biometricAvailability.status(),
            )
        }
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
        refreshProfile()
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

            is ProfileEvent.BiometricToggled -> toggleBiometric(event.enabled)
            ProfileEvent.BiometricPromptSucceeded -> setBiometricEnabled(true)
            ProfileEvent.BiometricPromptFailed -> updateState { copy(biometricPromptFailed = true) }
            ProfileEvent.BiometricPromptCancelled -> Unit

            // Интента может не быть (сборка без инспектора) — тогда и строки в
            // профиле нет, но событие из старого состояния экрана прилететь
            // может: молча ничего не делаем, а не падаем на startActivity(null).
            ProfileEvent.HttpInspectorRequested -> httpInspector.launchIntent()?.let { intent ->
                emitEffect(ProfileEffect.OpenHttpInspector(intent))
            }

            // Возврат на экран: вход с другого устройства мог случиться, пока
            // приложение было в фоне. Защита от дубля (первый resume, два
            // resume подряд) — общая, см. MviViewModel.onScreenResumed (issue
            // #145, #209); запрос по строке — тем более повод не грузить
            // список заново, иначе ответ приедет на уже сменившееся состояние.
            ProfileEvent.ScreenResumed -> {
                // Отпечаток могли добавить в настройках устройства и вернуться.
                updateState { copy(biometricStatus = biometricAvailability.status()) }
                onScreenResumed(
                    isLoadInFlight = {
                        currentState.pendingSessionId != null ||
                            sessionsJob?.isActive == true ||
                            profileRefreshJob?.isActive == true ||
                            // Пока идёт своё сохранение имени или аватара, `GET`
                            // не зовём: оба ответа переписывают профиль целиком,
                            // и `GET`, обогнавший ещё не пришедший `PUT`, стёр бы
                            // то, что человек только что сохранил.
                            nameSaveJob?.isActive == true ||
                            avatarJob?.isActive == true
                    },
                    load = {
                        loadSessions(showLoading = false)
                        refreshProfile()
                    },
                )
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

            ProfileEvent.NameEditRequested -> updateState {
                copy(nameEdit = NameEdit(editing = true, draft = profile.fullName.orEmpty()))
            }

            is ProfileEvent.NameDraftChanged -> updateState {
                copy(nameEdit = nameEdit.copy(draft = event.value))
            }

            ProfileEvent.NameEditCancelled -> {
                nameSaveJob?.cancel()
                nameSaveJob = null
                updateState { copy(nameEdit = NameEdit()) }
            }

            ProfileEvent.NameSaveRequested -> saveName()
        }
    }

    /**
     * Фото профиля (issue #101): сжать, отправить, привязать к аккаунту.
     *
     * Отправка на сервер — issue #170: `PUT users/me` принимает `avatarUrl`
     * (`docs/API-CONTRACT.md`), и его ответом переписывается локальный
     * профиль целиком — источник истины один. Файл при этом уже лежит на
     * сервере и числится за загрузившим (`ownerId`) независимо от исхода
     * `PUT`: отказ здесь не значит, что загрузка не удалась, только что адрес
     * не привязан к профилю.
     *
     * `entityId` — id пользователя, когда он известен: по нему загруженное
     * потом находится (`GET media/entity/{id}`). `entityType` не отправляется:
     * словаря его значений в схеме нет, а выдуманное значение бэкенд запомнит,
     * и разбирать это придётся руками.
     */
    private fun uploadAvatar(source: String) {
        // Имя сохраняется своим `PUT`: два одновременных `PUT` ответили бы в
        // произвольном порядке, и который приехал позже — тот и остался бы,
        // даже если сервер обработал их в обратном порядке. `profileRefreshJob`
        // тоже может быть в полёте своим `PUT` (при fullNamePendingSync, issue
        // #234) — те же два одновременных `PUT`, та же защита.
        if (
            currentState.avatarUpload.inProgress ||
            nameSaveJob?.isActive == true ||
            profileRefreshJob?.isActive == true
        ) return
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
                is ApiResult.Success -> when (
                    val saved = profileRepository.updateProfile(avatarUrl = result.data.url)
                ) {
                    is ApiResult.Success -> updateState { copy(avatarUpload = AvatarUpload()) }
                    is ApiResult.Failure -> updateState {
                        copy(avatarUpload = AvatarUpload(failure = saved.failure))
                    }
                }

                is ApiResult.Failure -> updateState {
                    copy(avatarUpload = AvatarUpload(failure = result.failure))
                }
            }
            avatarJob = null
        }
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
    /**
     * Выключить — сразу; включить — только после системного промпта. Без
     * датчика или без отпечатков включать нечего: строка для этого отключена
     * на экране, а сюда событие не должно доехать — если доехало, ничего не
     * пишем.
     */
    private fun toggleBiometric(enabled: Boolean) {
        updateState { copy(biometricPromptFailed = false) }
        if (!enabled) {
            setBiometricEnabled(false)
            return
        }
        if (currentState.biometricStatus != BiometricStatus.Available) return
        emitEffect(ProfileEffect.ShowBiometricPrompt)
    }

    private fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // Настройки могут не записаться (нет места, битый файл) — флаг не
            // повод падать: PIN остаётся входом, как и в онбординге.
            runCatchingCancellable { settingsDataStore.setBiometricEnabled(enabled) }
                .reportSwallowed("settings.setBiometricEnabled")
        }
    }

    private fun loadSessions(showLoading: Boolean = true) {
        sessionsJob = viewModelScope.launch {
            if (showLoading) updateState { copy(sessions = ScreenState.Loading) }
            val result = sessionsRepository.sessions()
            updateState { copy(sessions = result.toListScreenState()) }
        }
    }

    /**
     * `GET users/me` при открытии экрана и при возврате на него (issue #170).
     *
     * Если имя из анкеты покупателя ещё не подтверждено сервером
     * (`UserProfile.fullNamePendingSync`, issue #234), уходит не `GET`, а
     * повторный `PUT` — [ProfileRepository.refresh] сам решает, что отправить,
     * экрану это не видно.
     *
     * Отказ не трогает состояние: [UserProfileStore] уже хранит то, что
     * сохранил вход, и `profile` в шапке остаётся прежним — не пустым и не
     * заменённым ошибкой. Профиль здесь не главная причина открыть вкладку,
     * и молчаливо устаревший — не то же самое, что молчаливо пустой.
     */
    private fun refreshProfile() {
        profileRefreshJob = viewModelScope.launch {
            profileRepository.refresh()
            profileRefreshJob = null
        }
    }

    /**
     * Сохранить новое имя (issue #170). Пустое имя не отправляется: сервер
     * читает пустую строку как «снять значение», а стереть имя случайно
     * нажатием «сохранить» на пустом поле — не то, что должно происходить.
     * Длиннее `NameEdit.MAX_LENGTH` не отправляется тоже: сервер отклонит его
     * `VALIDATION_ERROR`, а показать это можно и без похода на сервер
     * (кнопка «Сохранить» уже неактивна, см. `NameEditor`).
     */
    private fun saveName() {
        val draft = currentState.nameEdit.draft.trim()
        if (draft.isEmpty() || draft.length > NameEdit.MAX_LENGTH) return
        // Аватар сохраняется своим `PUT` — см. `uploadAvatar`. `profileRefreshJob`
        // тоже может быть в полёте своим `PUT`, если анкета покупателя ждёт
        // подтверждения (issue #234, `fullNamePendingSync`) — не хватало бы
        // только двух одновременных `PUT` на разные имена.
        if (
            currentState.nameEdit.saving ||
            avatarJob?.isActive == true ||
            profileRefreshJob?.isActive == true
        ) {
            return
        }
        updateState { copy(nameEdit = nameEdit.copy(saving = true, failure = null)) }
        nameSaveJob = viewModelScope.launch {
            when (val result = profileRepository.updateProfile(fullName = draft)) {
                is ApiResult.Success -> updateState { copy(nameEdit = NameEdit()) }
                is ApiResult.Failure -> updateState {
                    copy(nameEdit = nameEdit.copy(saving = false, failure = result.failure))
                }
            }
            nameSaveJob = null
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
