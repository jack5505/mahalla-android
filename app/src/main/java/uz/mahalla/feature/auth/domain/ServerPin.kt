package uz.mahalla.feature.auth.domain

/**
 * PIN на стороне бэкенда (issue #51).
 *
 * Проверка SMS-кода токенов не выдаёт: `auth/verify-otp` отвечает 200,
 * создаёт сессию и говорит `nextStep`. Токены приезжают только следующим
 * запросом — `auth/setup-pin` (по `sessionId`) либо `auth/pin-login` (по
 * устройству). Пока приложение этот шаг не делало, верный код заканчивался
 * ошибкой «Nimadir xato ketdi»: `tokens` в ответе нет, и клиент считал ответ
 * неразобранным.
 */
enum class ServerPinStep {
    /** У аккаунта PIN ещё не задан — его надо придумать и отправить. */
    Setup,

    /** PIN уже есть: ввод проверяет сервер, он же выдаёт токены. */
    Enter,
}

/**
 * Что осталось сделать, чтобы вход завершился.
 *
 * @param sessionId нужен только для [ServerPinStep.Setup]; `pin-login`
 * опознаёт пользователя по устройству.
 */
data class ServerPinChallenge(
    val step: ServerPinStep,
    val sessionId: String? = null,
)

/** Итог проверки SMS-кода. */
sealed interface VerificationResult {

    /** Данные пользователя доехали в любом случае — экраны дальше их ждут. */
    val login: LoginResult

    /** Токены выданы сразу, сессия сохранена: `nextStep = NONE`. */
    data class Authorized(override val login: LoginResult) : VerificationResult

    /**
     * Код принят, но сессия ещё без токенов: бэкенд ждёт PIN. Сохранять
     * нечего — до [challenge] вход не состоялся.
     */
    data class PinRequired(
        val challenge: ServerPinChallenge,
        override val login: LoginResult,
    ) : VerificationResult
}

object ServerPin {

    /**
     * Ровно шесть цифр — требование бэкенда (`^[0-9]{6}$` в схемах
     * `SetupPinRequest`/`PinLoginRequest`). Локальный PIN приложения тем же
     * кодом и остаётся: два разных PIN на один вход человек не различит.
     */
    const val LENGTH = 6

    private const val SETUP = "SETUP_PIN"
    private const val ENTER = "ENTER_PIN"

    /**
     * Разбор `nextStep`. Незнакомое или отсутствующее значение — не повод
     * сдаться: раз токенов нет, шаг с PIN всё равно предстоит, а какой именно,
     * подсказывает [pinConfigured] из ответа (`user.pinSetup`).
     */
    fun stepOf(nextStep: String?, pinConfigured: Boolean): ServerPinStep =
        when (nextStep?.trim()?.uppercase()) {
            SETUP -> ServerPinStep.Setup
            ENTER -> ServerPinStep.Enter
            else -> if (pinConfigured) ServerPinStep.Enter else ServerPinStep.Setup
        }
}
