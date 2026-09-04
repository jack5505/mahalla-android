package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.profile.data.SessionsRepository
import uz.mahalla.feature.profile.domain.DeviceSession

/**
 * Устройства в памяти: ViewModel профиля проверяется без MockWebServer.
 * Ответы подменяются полями, вызовы записываются — тест видит и результат, и
 * то, что запрос вообще ушёл.
 */
class FakeSessionsRepository(
    initial: List<DeviceSession> = emptyList(),
) : SessionsRepository {

    var sessionsResult: ApiResult<List<DeviceSession>> = ApiResult.Success(initial)
    var revokeResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var trustResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var sessionsCount: Int = 0
        private set
    val revoked = mutableListOf<String>()
    val trustChanges = mutableListOf<Pair<String, Boolean>>()

    override suspend fun sessions(): ApiResult<List<DeviceSession>> {
        sessionsCount++
        return sessionsResult
    }

    override suspend fun revoke(sessionId: String): ApiResult<Unit> {
        revoked += sessionId
        return revokeResult
    }

    override suspend fun setTrusted(sessionId: String, trusted: Boolean): ApiResult<Unit> {
        trustChanges += sessionId to trusted
        return trustResult
    }
}
