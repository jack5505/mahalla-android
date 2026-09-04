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
import uz.mahalla.feature.profile.domain.DeviceSession

/**
 * @param httpInspectorAvailable в сборке есть инспектор трафика (issue #30) —
 * показываем строку «сетевые запросы». В release её нет.
 * @param profile кто вошёл. Приезжает с ответом на вход и лежит в DataStore:
 * `GET /users/me` у бэкенда нет (issue #61).
 * @param sessions устройства, на которых открыт вход.
 * @param pendingSessionId строка списка, на которой сейчас идёт запрос:
 * отзыв и доверие блокируются точечно, а не всем экраном.
 * @param sessionFailure отказ отзыва или доверия — показывается текстом
 * сервера рядом со списком (issue #34), а не молча теряется.
 * @param confirmLogout показан диалог подтверждения выхода.
 * @param confirmRevoke устройство, которое собираются отозвать.
 * @param loggingOut выход уже идёт: повторные нажатия не плодят запросов.
 * @param avatarUpload загрузка фото профиля (issue #101).
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
) : UiState

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
