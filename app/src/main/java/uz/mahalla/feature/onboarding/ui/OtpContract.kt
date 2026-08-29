package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.auth.domain.OtpDeliveryChannel
import uz.mahalla.feature.auth.domain.OtpFailure

data class OtpState(
    val phone: String = "",
    /**
     * Токен отправленного кода (issue #42): по нему бэкенд проверяет ввод, и
     * повторная отправка его заменяет.
     */
    val otpToken: String = "",
    /**
     * Куда бэкенд отправил код (issue #54). Экран без этого говорил «код
     * отправлен на +998…», а код в это время лежал в Telegram — человек ждал
     * SMS, которого не будет.
     */
    val channel: OtpDeliveryChannel = OtpDeliveryChannel.Sms,
    /**
     * Пакет Telegram-клиента на устройстве или `null`. Кнопку «Открыть
     * Telegram» без него показывать нельзя: открывать нечего.
     */
    val telegramPackage: String? = null,
    val code: OtpFieldState = OtpFieldState(),
    val submitting: Boolean = false,
    /** Сколько секунд осталось до разблокировки «Отправить снова». */
    val resendInSeconds: Int = 0,
    val resending: Boolean = false,
    val failure: OtpFailure? = null,
    /**
     * Ошибка запроса отдельным блоком под полем: сеть, отказ бэкенда с
     * объяснением (issue #34). То, что уже сказано подписью поля («код
     * неверный»), сюда не попадает — см. [OtpViewModel].
     */
    val apiFailure: ApiFailure? = null,
) : UiState {
    /**
     * Лимит попыток исчерпан или код истёк: вводить дальше бессмысленно,
     * пока не придёт новый код.
     */
    val inputBlocked: Boolean
        get() = failure == OtpFailure.TooManyAttempts || failure == OtpFailure.Expired

    /**
     * Текст бэкенда идёт подписью под ячейками: он точнее собственного «код
     * неверный» и говорит ровно про то же поле. Иначе человек читает два
     * разных объяснения одного отказа в двух местах экрана (issue #34).
     */
    val fieldError: String? get() = apiFailure?.serverMessage

    /**
     * Отдельный блок повторяет текст, только если под полем его нет: сеть,
     * пустой ответ, HTML от прокси. Подробности ответа блок показывает всегда.
     */
    val showApiMessage: Boolean get() = fieldError == null

    /** Код пришёл сообщением бота, а не SMS — экран объясняет это отдельно. */
    val isTelegramChannel: Boolean get() = channel == OtpDeliveryChannel.Telegram

    /**
     * Показывать ли кнопку «Открыть Telegram»: только когда код действительно
     * там и клиент на устройстве есть. Иначе кнопка вела бы в никуда.
     */
    val canOpenTelegram: Boolean get() = isTelegramChannel && telegramPackage != null

    val canResend: Boolean get() = resendInSeconds == 0 && !resending && !submitting

    val canSubmit: Boolean get() = code.isComplete && !submitting && !inputBlocked
}

sealed interface OtpEvent : UiEvent {
    data class CodeChanged(val raw: String) : OtpEvent
    data object Submit : OtpEvent
    data object Resend : OtpEvent
    data object ErrorDismissed : OtpEvent

    /** «Открыть Telegram» — забрать код из чата с ботом (issue #54). */
    data object OpenTelegramRequested : OtpEvent
}

sealed interface OtpEffect : UiEffect {
    /** Код принят, сессия сохранена — дальше PIN. */
    data class Verified(val isNewUser: Boolean) : OtpEffect

    /** Новый код ушёл — сообщаем пользователю снекбаром. */
    data object CodeResent : OtpEffect

    /**
     * Открыть Telegram, чтобы забрать код у бота.
     *
     * Адреса чата здесь нет намеренно: `auth/send-otp` ссылку на бота не
     * отдаёт (проверено на стенде — в ответе только `channel`), а выдумывать
     * имя бота на клиенте нельзя. Открываем само приложение: сообщение от бота
     * в списке чатов будет последним.
     */
    data class OpenTelegram(val packageName: String) : OtpEffect
}
