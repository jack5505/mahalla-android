package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.ServerPinChallenge
import uz.mahalla.feature.auth.domain.TelegramChallenge
import uz.mahalla.feature.auth.domain.TelegramLoginState
import uz.mahalla.feature.auth.domain.VerificationResult

/**
 * Авторизация в памяти: ViewModel'и онбординга не должны знать ни про сеть,
 * ни про DataStore. Ответы подменяются полями, вызовы записываются — тесты
 * проверяют и результат, и то, что запрос вообще ушёл.
 */
class FakeAuthRepository(
    initialAuthorized: Boolean = false,
) : AuthRepository {

    var requestCodeResult: ApiResult<OtpChallenge> =
        ApiResult.Success(OtpChallenge(otpToken = DEFAULT_OTP_TOKEN))
    var verifyResult: ApiResult<VerificationResult> =
        ApiResult.Success(VerificationResult.Authorized(LoginResult(isNewUser = false)))
    var refreshResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Ответ `setup-pin`/`pin-login` (issue #51). */
    var completeServerPinResult: ApiResult<LoginResult> =
        ApiResult.Success(LoginResult(isNewUser = false))

    /** PIN'ы, ушедшие на бэкенд: тест проверяет, что запрос вообще случился. */
    val completedServerPins = mutableListOf<String>()

    var telegramStartResult: ApiResult<TelegramChallenge> = ApiResult.Success(
        TelegramChallenge(deepLinkToken = DEFAULT_DEEP_LINK_TOKEN, botUrl = DEFAULT_BOT_URL),
    )

    /**
     * Ответы `check` по порядку: первый вызов забирает первый элемент и так
     * далее, последний повторяется дальше. Так тест описывает сценарий
     * «подождали — подождали — подтвердили» одной строкой.
     */
    var telegramCheckResults: List<ApiResult<TelegramLoginState>> =
        listOf(ApiResult.Success(TelegramLoginState.Pending))

    val requestedPhones = mutableListOf<String>()
    /** Пары «токен кода — введённый код»: токен приезжает из `requestCode`. */
    val verifiedCodes = mutableListOf<Pair<String, String>>()
    val telegramChecks = mutableListOf<String>()
    var telegramStartCount: Int = 0
        private set
    var logoutCount: Int = 0
        private set

    private val authorized = MutableStateFlow(initialAuthorized)

    override val isAuthorized: Flow<Boolean> = authorized

    override suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge> {
        requestedPhones += phoneE164
        return requestCodeResult
    }

    override var pendingServerPin: ServerPinChallenge? = null

    override suspend fun verifyCode(
        otpToken: String,
        code: String,
    ): ApiResult<VerificationResult> {
        verifiedCodes += otpToken to code
        val result = verifyResult
        if (result is ApiResult.Success) {
            when (val verification = result.data) {
                is VerificationResult.Authorized -> authorized.value = true
                // Токенов ещё нет: сессию выдаст только PIN-шаг.
                is VerificationResult.PinRequired ->
                    pendingServerPin = verification.challenge
            }
        }
        return result
    }

    override suspend fun completeServerPin(pin: String): ApiResult<LoginResult> {
        completedServerPins += pin
        val result = completeServerPinResult
        if (result is ApiResult.Success) {
            authorized.value = true
            pendingServerPin = null
        }
        return result
    }

    override suspend fun startTelegramLogin(): ApiResult<TelegramChallenge> {
        telegramStartCount++
        return telegramStartResult
    }

    override suspend fun checkTelegramLogin(
        deepLinkToken: String,
    ): ApiResult<TelegramLoginState> {
        telegramChecks += deepLinkToken
        val index = (telegramChecks.size - 1).coerceAtMost(telegramCheckResults.lastIndex)
        val result = telegramCheckResults[index]
        if (result is ApiResult.Success && result.data is TelegramLoginState.Confirmed) {
            // Настоящий репозиторий на этом шаге сохраняет сессию — кроме
            // случая, когда номер ещё не подтверждён.
            val confirmed = result.data as TelegramLoginState.Confirmed
            if (!confirmed.requiresPhoneVerify) authorized.value = true
        }
        return result
    }

    override suspend fun refresh(): ApiResult<Unit> = refreshResult

    override suspend fun logout() {
        logoutCount++
        authorized.value = false
        pendingServerPin = null
    }

    companion object {
        const val DEFAULT_OTP_TOKEN = "otp-token"
        const val DEFAULT_DEEP_LINK_TOKEN = "deep-link-token"
        const val DEFAULT_BOT_URL = "https://t.me/MahallaVerifyBot?start=deep-link-token"
    }
}
