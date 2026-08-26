package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge

/**
 * Авторизация в памяти: ViewModel'и онбординга не должны знать ни про сеть,
 * ни про DataStore. Ответы подменяются полями, вызовы записываются — тесты
 * проверяют и результат, и то, что запрос вообще ушёл.
 */
class FakeAuthRepository(
    initialAuthorized: Boolean = false,
) : AuthRepository {

    var requestCodeResult: ApiResult<OtpChallenge> = ApiResult.Success(OtpChallenge())
    var verifyResult: ApiResult<LoginResult> = ApiResult.Success(LoginResult(isNewUser = false))
    var refreshResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val requestedPhones = mutableListOf<String>()
    val verifiedCodes = mutableListOf<Pair<String, String>>()
    var logoutCount: Int = 0
        private set

    private val authorized = MutableStateFlow(initialAuthorized)

    override val isAuthorized: Flow<Boolean> = authorized

    override suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge> {
        requestedPhones += phoneE164
        return requestCodeResult
    }

    override suspend fun verifyCode(phoneE164: String, code: String): ApiResult<LoginResult> {
        verifiedCodes += phoneE164 to code
        if (verifyResult is ApiResult.Success) authorized.value = true
        return verifyResult
    }

    override suspend fun refresh(): ApiResult<Unit> = refreshResult

    override suspend fun logout() {
        logoutCount++
        authorized.value = false
    }
}
