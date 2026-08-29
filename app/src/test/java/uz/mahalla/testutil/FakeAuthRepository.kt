package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.TelegramChallenge
import uz.mahalla.feature.auth.domain.TelegramLoginState

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
    var verifyResult: ApiResult<LoginResult> = ApiResult.Success(LoginResult(isNewUser = false))
    var refreshResult: ApiResult<Unit> = ApiResult.Success(Unit)

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

    override suspend fun verifyCode(otpToken: String, code: String): ApiResult<LoginResult> {
        verifiedCodes += otpToken to code
        if (verifyResult is ApiResult.Success) authorized.value = true
        return verifyResult
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
    }

    companion object {
        const val DEFAULT_OTP_TOKEN = "otp-token"
        const val DEFAULT_DEEP_LINK_TOKEN = "deep-link-token"
        const val DEFAULT_BOT_URL = "https://t.me/MahallaVerifyBot?start=deep-link-token"
    }
}
