package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.auth.domain.ServerPin
import uz.mahalla.feature.auth.domain.ServerPinStep

/** Этап работы с PIN (3.4). */
enum class PinStage {
    /** Придумать код. */
    Create,

    /** Повторить придуманный код. */
    Confirm,

    /** Ввести уже сохранённый код — вход на устройстве, где PIN настроен. */
    Unlock,
}

enum class PinError {
    /** Повтор не совпал с первым вводом. */
    MISMATCH,

    /** Сохранённый PIN не подошёл. */
    WRONG_PIN,

    /** Попытки исчерпаны: PIN сброшен, нужен вход заново. */
    TOO_MANY_ATTEMPTS,

    /** Хранилище (Keystore) не смогло сохранить или проверить код. */
    STORAGE,
}

data class PinState(
    val stage: PinStage = PinStage.Create,
    val pin: OtpFieldState = OtpFieldState(length = PIN_LENGTH),
    /** Осталось попыток на этапе [PinStage.Unlock]. */
    val attemptsLeft: Int = MAX_ATTEMPTS,
    val busy: Boolean = false,
    val error: PinError? = null,
    /**
     * Шаг, которого ждёт бэкенд (issue #51). `null` — PIN нужен только
     * устройству, сессия уже есть: сеть на этом экране не участвует.
     */
    val serverStep: ServerPinStep? = null,
    /**
     * Отказ бэкенда на `setup-pin`/`pin-login` с его же объяснением
     * (issue #34): «PIN noto'g'ri, 2 urinish qoldi» точнее любого своего
     * текста, а после блокировки только сервер знает, насколько.
     */
    val apiFailure: ApiFailure? = null,
) : UiState {

    /**
     * Счётчик попыток ведёт сервер: свой лимит стёр бы PIN и сессию раньше
     * времени, а сообщения об оставшихся попытках расходились бы.
     */
    val countsAttemptsLocally: Boolean get() = serverStep == null

    companion object {
        /**
         * Шесть цифр: столько требует бэкенд (issue #51), и тот же код
         * становится локальным PIN. Прежний четырёхзначный, если он где-то
         * сохранён, продолжает открывать экран блокировки — длину поля даёт
         * `PinStorage.configuredLength()`.
         */
        const val PIN_LENGTH = ServerPin.LENGTH

        /** Пять попыток, затем PIN сбрасывается и требуется вход по SMS. */
        const val MAX_ATTEMPTS = 5
    }
}

sealed interface PinEvent : UiEvent {
    data class PinChanged(val raw: String) : PinEvent

    /** «Забыли PIN» — выход и вход заново по номеру телефона. */
    data object ForgotPin : PinEvent
    data object ErrorDismissed : PinEvent
}

sealed interface PinEffect : UiEffect {
    /** PIN установлен или подтверждён — дальше биометрия. */
    data object PinReady : PinEffect

    /** Сессия сброшена: PIN не восстановить, нужен вход по SMS с начала. */
    data object AuthRestartRequired : PinEffect
}
