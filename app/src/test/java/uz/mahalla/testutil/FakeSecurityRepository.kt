package uz.mahalla.testutil

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.security.data.SecurityRepository
import uz.mahalla.feature.security.domain.ServerPinStatus
import uz.mahalla.feature.security.domain.SessionCheck

/** Аккаунтный PIN и app-lock (issue #102) в памяти — без сети и без DI. */
class FakeSecurityRepository(
    var status: ApiResult<ServerPinStatus> = ApiResult.Success(
        ServerPinStatus(pinSet = true, biometricEnabled = false, lockedSecondsRemaining = 0),
    ),
    var sessionCheck: ApiResult<SessionCheck> = ApiResult.Success(
        SessionCheck(valid = true, pinRequired = false, reason = null),
    ),
    var changeResult: ApiResult<Unit> = ApiResult.Success(Unit),
    var biometricResult: ApiResult<Boolean>? = null,
    var resumeResult: ApiResult<Unit> = ApiResult.Success(Unit),
) : SecurityRepository {

    /** Пары «текущий, новый», ушедшие в `pin/change`. */
    val changeCalls = mutableListOf<Pair<String, String>>()

    /** Пары «включить, PIN», ушедшие в `pin/biometric`. */
    val biometricCalls = mutableListOf<Pair<Boolean, String>>()

    /** Коды, ушедшие в `auth/pin-resume`. */
    val resumeCalls = mutableListOf<String>()

    var sessionCheckCount: Int = 0
        private set

    override suspend fun pinStatus(): ApiResult<ServerPinStatus> = status

    override suspend fun changePin(currentPin: String, newPin: String): ApiResult<Unit> {
        changeCalls += currentPin to newPin
        return changeResult
    }

    override suspend fun setBiometricEnabled(
        enabled: Boolean,
        pin: String,
    ): ApiResult<Boolean> {
        biometricCalls += enabled to pin
        // По умолчанию сервер соглашается с тем, о чём просили: отдельное
        // значение нужно только тесту про «ответил не тем».
        return biometricResult ?: ApiResult.Success(enabled)
    }

    override suspend fun checkSession(): ApiResult<SessionCheck> {
        sessionCheckCount++
        return sessionCheck
    }

    override suspend fun resumeSession(pin: String): ApiResult<Unit> {
        resumeCalls += pin
        return resumeResult
    }

    companion object {
        /** Отказ без ответа сервера — то, что приезжает при пропавшей сети. */
        val NETWORK_FAILURE: ApiResult<Nothing> = ApiResult.Failure(ApiError.NoConnection)
    }
}
