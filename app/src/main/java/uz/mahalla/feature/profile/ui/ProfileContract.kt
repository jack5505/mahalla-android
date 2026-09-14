package uz.mahalla.feature.profile.ui

import android.content.Intent
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.ThemeMode
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.profile.domain.AccountStatus
import uz.mahalla.feature.profile.domain.DeviceSession
import uz.mahalla.feature.profile.domain.VerificationStatus
import uz.mahalla.feature.role.domain.ServerRole
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.role.domain.providesServices

/**
 * @param httpInspectorAvailable в сборке есть инспектор трафика (issue #30) —
 * показываем строку «сетевые запросы». В release её нет.
 * @param profile кто вошёл. Источник — `GET /users/me`, перечитанный при
 * открытии экрана (issue #170); пока ответа нет или он не пришёл, здесь
 * лежит то, что сохранил вход — экран не бывает пустым.
 * @param sessions устройства, на которых открыт вход.
 * @param pendingSessionId строка списка, на которой сейчас идёт запрос:
 * отзыв и доверие блокируются точечно, а не всем экраном.
 * @param sessionFailure отказ отзыва или доверия — показывается текстом
 * сервера рядом со списком (issue #34), а не молча теряется.
 * @param confirmLogout показан диалог подтверждения выхода.
 * @param confirmRevoke устройство, которое собираются отозвать.
 * @param loggingOut выход уже идёт: повторные нажатия не плодят запросов.
 * @param avatarUpload загрузка фото профиля (issue #101).
 * @param nameEdit редактирование имени через `PUT users/me` (issue #170).
 */
data class ProfileState(
    val settings: AppSettings = AppSettings(),
    val httpInspectorAvailable: Boolean = false,
    val profile: UserProfile = UserProfile(),
    val sessions: ScreenState<List<DeviceSession>> = ScreenState.Loading,
    val pendingSessionId: String? = null,
    val sessionFailure: ApiFailure? = null,
    val confirmLogout: Boolean = false,
    val confirmRevoke: DeviceSession? = null,
    val loggingOut: Boolean = false,
    val avatarUpload: AvatarUpload = AvatarUpload(),
    val nameEdit: NameEdit = NameEdit(),
) : UiState {

    /** Роль из анкеты — локальный выбор человека (issue #84). */
    val formRole: UserRole? get() = UserRole.fromStoredValue(settings.roleId)

    /** Права на сервере: их приложение не выбирает и не меняет (issue #237). */
    val serverRole: ServerRole get() = ServerRole.fromServer(profile.serverRole)

    val verification: VerificationStatus
        get() = VerificationStatus.fromServer(profile.verificationStatus)

    val account: AccountStatus get() = AccountStatus.fromServer(profile.accountStatus)

    /**
     * Показывать ли «Мои заведения» (issue #237).
     *
     * Два условия, а не одно: анкета продавца — это заявка, а не право, и
     * человек может её не заполнять; серверная роль — право, и владелец
     * заведения, который анкету не заполнял, до issue #237 своего заведения в
     * приложении не находил вовсе. Ложное «да» стоит пустого списка, ложное
     * «нет» — спрятанного бизнеса. Правило общее с аудиторией тарифов
     * подписки (issue #244) — см. [providesServices].
     */
    val showMyPlaces: Boolean get() = providesServices(formRole, serverRole)
}

/**
 * Состояние загрузки фото профиля (issue #101).
 *
 * @param percent доля отправленного, 0..100. Показывается полоской: на
 * медленной связи неподвижная крутилка неотличима от зависшего экрана.
 * @param failure отказ — и сетевой (текстом сервера, issue #34), и клиентский
 * (файл не читается, не картинка, не влезает даже сжатым).
 */
data class AvatarUpload(
    val inProgress: Boolean = false,
    val percent: Int = 0,
    val failure: ApiFailure? = null,
)

/**
 * Редактирование имени (issue #170): `PUT users/me` принимает `fullName`
 * ≤ 200 символов (`docs/API-CONTRACT.md`).
 *
 * @param editing поле открыто на редактирование — иначе шапка просто
 * показывает `profile.fullName`.
 * @param draft то, что человек сейчас набирает; своё поле, а не
 * `profile.fullName` напрямую — иначе ответ `GET`, перечитавшего профиль
 * посреди набора текста, стёр бы недописанное имя.
 * @param saving запрос уже идёт: повторное нажатие «сохранить» не плодит
 * второй.
 * @param failure отказ сохранения — текстом сервера (issue #34), а не молча.
 */
data class NameEdit(
    val editing: Boolean = false,
    val draft: String = "",
    val saving: Boolean = false,
    val failure: ApiFailure? = null,
) {
    companion object {
        const val MAX_LENGTH = 200
    }
}

sealed interface ProfileEvent : UiEvent {
    data class LanguageSelected(val language: AppLanguage) : ProfileEvent
    data class ThemeSelected(val mode: ThemeMode) : ProfileEvent
    data object HttpInspectorRequested : ProfileEvent

    /** Экран вернулся на передний план: список устройств мог устареть. */
    data object ScreenResumed : ProfileEvent

    data object SessionsRetryRequested : ProfileEvent

    data object LogoutRequested : ProfileEvent
    data object LogoutConfirmed : ProfileEvent
    data object LogoutDismissed : ProfileEvent

    data class SessionRevokeRequested(val session: DeviceSession) : ProfileEvent
    data object SessionRevokeConfirmed : ProfileEvent
    data object SessionRevokeDismissed : ProfileEvent

    data class SessionTrustToggled(val session: DeviceSession, val trusted: Boolean) : ProfileEvent

    /**
     * Выбрано фото профиля (issue #101). Адрес приходит строкой: `Uri` — тип
     * Android, а ViewModel проверяется на чистом JVM.
     */
    data class AvatarPicked(val source: String) : ProfileEvent

    /** Отмена загрузки: файл дописан не будет, сервер его не получит. */
    data object AvatarUploadCancelled : ProfileEvent

    /** Нажали на имя в шапке (issue #170): открыть поле редактирования. */
    data object NameEditRequested : ProfileEvent

    data class NameDraftChanged(val value: String) : ProfileEvent

    /** Закрыть поле без сохранения — не считается отказом, сервер не звался. */
    data object NameEditCancelled : ProfileEvent

    data object NameSaveRequested : ProfileEvent
}

sealed interface ProfileEffect : UiEffect {
    /** До API 33 смену языка применяет только пересоздание Activity. */
    data object RecreateActivity : ProfileEffect

    /** Экран инспектора трафика: интент отдаёт сама библиотека (issue #30). */
    data class OpenHttpInspector(val intent: Intent) : ProfileEffect

    /**
     * Вышли: сессии и PIN больше нет, приложение возвращается в онбординг.
     * Навигацию делает граф — ViewModel про маршруты не знает.
     */
    data object LoggedOut : ProfileEffect
}
