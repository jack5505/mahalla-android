package uz.mahalla.feature.onboarding.ui

import android.content.Intent
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState

enum class BackendUrlError {
    /** Строка не разбирается в http(s)-адрес. */
    INVALID,

    /**
     * Адрес разобран, но сборке запрещён незашифрованный `http` на этот хост
     * (network-security-config). Сохранять такой адрес бессмысленно: ни один
     * запрос по нему не уйдёт.
     */
    CLEARTEXT_BLOCKED,

    /** Адрес разобран, но сервер не ответил. */
    UNREACHABLE,
}

/**
 * Экран ввода адреса бэкенда (issue #26).
 *
 * @param checked адрес уже проверялся и не ответил: повторный тап сохраняет
 * его как есть. Иначе пользователь за NAT'ом или с сервером, не отвечающим на
 * `HEAD /`, не смог бы продолжить вообще.
 */
data class BackendUrlState(
    val url: String = "",
    val checking: Boolean = false,
    val error: BackendUrlError? = null,
    val checked: Boolean = false,
    /** Адрес из сборки — предложение «вернуть как было». */
    val defaultUrl: String = "",
    /**
     * В сборке есть инспектор трафика (issue #30). Кнопка нужна именно здесь:
     * до входа профиль недоступен, а посмотреть, куда и с каким телом ушёл
     * запрос кода из SMS, чаще всего требуется как раз на этом шаге.
     */
    val httpInspectorAvailable: Boolean = false,
) : UiState {
    val canSubmit: Boolean get() = url.isNotBlank() && !checking
}

sealed interface BackendUrlEvent : UiEvent {
    data class UrlChanged(val raw: String) : BackendUrlEvent
    data object Submit : BackendUrlEvent
    data object DefaultRequested : BackendUrlEvent
    data object HttpInspectorRequested : BackendUrlEvent
}

sealed interface BackendUrlEffect : UiEffect {
    /** Адрес применён — можно уходить с экрана. */
    data object Saved : BackendUrlEffect

    /** Экран инспектора трафика: интент отдаёт сама библиотека (issue #30). */
    data class OpenHttpInspector(val intent: Intent) : BackendUrlEffect
}
