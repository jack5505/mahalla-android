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

    /**
     * Вход состоялся, сессия сохранена — экран уходит эффектом
     * [TelegramEffect.Confirmed]. Статус нужен, чтобы до навигации на экране не
     * крутилось «ждём подтверждения»: ждать уже нечего.
     */
    CONFIRMED,

    /**
     * Telegram подтвердил личность, но номер аккаунта бэкенд считает
     * непроверенным (`requiresPhoneVerify`) — вход придётся добить кодом из
     * SMS.
     *
     * Отдельный статус, а не молчаливый возврат к форме номера: раньше
     * приложение в этом случае только слало одноразовый эффект, оставаясь в
     * [WAITING], — человек видел бесконечную крутилку и никакого объяснения
     * (issue #49).
     */
    PHONE_VERIFY,

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
    /**
     * Номер, который бот сообщил бэкенду. Заполнен только при
     * [TelegramStatus.PHONE_VERIFY] и только если сервер его прислал — экран
     * называет человеку номер, который предстоит подтвердить.
     */
    val phone: String? = null,
    val apiFailure: ApiFailure? = null,
) : UiState {

    /** Ссылку можно открыть ещё раз. */
    val canOpenBot: Boolean get() = status == TelegramStatus.WAITING && botUrl != null

    /** Есть смысл начать заново — с новым токеном. */
    val canRetry: Boolean get() = status == TelegramStatus.EXPIRED || status == TelegramStatus.FAILED

    /**
     * Дальше только SMS: Telegram своё отработал, но номер не подтверждён.
     * Кнопка перехода становится главной — это единственный осмысленный шаг.
     */
    val needsPhoneVerify: Boolean get() = status == TelegramStatus.PHONE_VERIFY

    /** Ждать больше нечего — крутилку показывать нельзя. */
    val isWaiting: Boolean get() = status == TelegramStatus.WAITING
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
     * Пользователь выбрал SMS вместо Telegram — сам или потому, что бэкенд
     * попросил подтвердить номер ([TelegramStatus.PHONE_VERIFY]). Уход на
     * SMS в обоих случаях делается тапом, а не сам собой: молчаливый возврат к
     * форме номера человек читает как «ничего не произошло» (issue #49).
     */
    data object SwitchToSms : TelegramEffect
}
