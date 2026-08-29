package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState

/** Стадия входа через Telegram (issue #46). */
enum class TelegramStatus {
    /** Идёт `auth/telegram/init` — ссылки на бота ещё нет. */
    PREPARING,

    /** Бот открыт, опрашиваем `auth/telegram/check`. */
    WAITING,

    /** Токен истёк: Start так и не нажали. Нужна новая попытка. */
    EXPIRED,

    /** Отказ, который повторять бессмысленно. Подробности — в [TelegramState.apiFailure]. */
    FAILED,
}

data class TelegramState(
    val status: TelegramStatus = TelegramStatus.PREPARING,
    /**
     * Ссылка на бота — уже проверенная (`TelegramBotLink`). Нужна экрану,
     * чтобы человек мог открыть Telegram повторно: с первого раза окно могло
     * не появиться, или он вернулся, не нажав Start.
     */
    val botUrl: String? = null,
    val apiFailure: ApiFailure? = null,
) : UiState {

    /** Ссылку можно открыть ещё раз. */
    val canOpenBot: Boolean get() = status == TelegramStatus.WAITING && botUrl != null

    /** Есть смысл начать заново — с новым токеном. */
    val canRetry: Boolean get() = status == TelegramStatus.EXPIRED || status == TelegramStatus.FAILED
}

sealed interface TelegramEvent : UiEvent {
    /** Открыть бота ещё раз. */
    data object OpenBotRequested : TelegramEvent

    /**
     * Экран снова на переднем плане — вероятнее всего, человек только что
     * нажал Start и вернулся. Проверяем немедленно, не дожидаясь паузы.
     */
    data object ScreenResumed : TelegramEvent

    /** Начать заново: новый `init`, новый токен. */
    data object RetryRequested : TelegramEvent

    /** Отказаться от Telegram и уйти на обычный SMS-путь. */
    data object SmsRequested : TelegramEvent
}

sealed interface TelegramEffect : UiEffect {
    /**
     * Открыть чат с ботом.
     *
     * [url] уже проверен на принадлежность Telegram (`TelegramBotLink`), а
     * [packageName] адресует intent конкретному клиенту. Без адресата ссылку
     * `https://t.me/…` перехватил бы браузер (или приложение, объявившее тот же
     * хост) — и одноразовый токен входа уехал бы не туда.
     */
    data class OpenBot(val url: String, val packageName: String?) : TelegramEffect

    /** Вход состоялся, сессия сохранена. */
    data class Confirmed(val isNewUser: Boolean) : TelegramEffect

    /**
     * Telegram подтвердил личность, но номер аккаунта не проверен — дальше
     * обычный SMS-путь. Сессия при этом не сохранена (см. `AuthRepository`).
     */
    data object PhoneVerificationRequired : TelegramEffect

    /** Пользователь выбрал SMS вместо Telegram. */
    data object SwitchToSms : TelegramEffect
}
