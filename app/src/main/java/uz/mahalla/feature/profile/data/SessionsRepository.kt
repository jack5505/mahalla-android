package uz.mahalla.feature.profile.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.feature.profile.domain.DeviceSession
import uz.mahalla.feature.profile.domain.DeviceSessionStatus
import uz.mahalla.feature.profile.domain.sortedForDisplay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Устройства, на которых открыт вход (issue #61).
 *
 * Интерфейс — ради теста ViewModel: список устройств проверяется без
 * MockWebServer.
 */
interface SessionsRepository {

    suspend fun sessions(): ApiResult<List<DeviceSession>>

    /** Погасить чужую сессию. Своя гасится выходом, а не отзывом. */
    suspend fun revoke(sessionId: String): ApiResult<Unit>

    suspend fun setTrusted(sessionId: String, trusted: Boolean): ApiResult<Unit>
}

@Singleton
class DefaultSessionsRepository @Inject constructor(
    private val sessionsApi: SessionsApi,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val sessionStore: SessionStore,
) : SessionsRepository {

    override suspend fun sessions(): ApiResult<List<DeviceSession>> {
        val device = deviceInfoProvider.current()
        // Своя сессия известна и локально: если бэкенд не проставил
        // `currentDevice`, устройство всё равно не должно предлагать себя к
        // отзыву.
        val currentSessionId = sessionStore.current()?.sessionId

        return apiCall {
            sessionsApi.sessions(
                deviceId = device.deviceId,
                platform = device.platform,
                osVersion = device.osVersion,
            ).payload()
        }.map { dtos -> dtos.toDomain(currentSessionId) }
    }

    override suspend fun revoke(sessionId: String): ApiResult<Unit> = apiCall {
        sessionsApi.revoke(
            RevokeSessionRequest(sessionId = sessionId, revokeAll = false),
        ).ensureSuccess()
    }

    override suspend fun setTrusted(sessionId: String, trusted: Boolean): ApiResult<Unit> =
        apiCall { sessionsApi.trust(sessionId = sessionId, trusted = trusted).ensureSuccess() }
}

/**
 * Разбор мягкий, как в каталоге: запись без `sessionId` отбрасывается (её
 * нечем ни отозвать, ни отличить от соседней), погашенные сессии в списке
 * «мои устройства» не показываются — это уже не вход, а история.
 */
internal fun List<ActiveSessionDto>.toDomain(currentSessionId: String?): List<DeviceSession> =
    mapNotNull { dto ->
        val id = dto.sessionId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val status = DeviceSessionStatus.fromServer(dto.status)
        if (status == DeviceSessionStatus.Revoked) return@mapNotNull null
        DeviceSession(
            id = id,
            deviceName = dto.deviceName?.takeIf { it.isNotBlank() },
            platform = dto.platform?.takeIf { it.isNotBlank() },
            appVersion = dto.appVersion?.takeIf { it.isNotBlank() },
            status = status,
            lastActivityAt = parseServerInstant(dto.lastActivityAt),
            lastIp = dto.lastIp?.takeIf { it.isNotBlank() },
            trusted = dto.trustedDevice,
            isCurrent = dto.currentDevice || (currentSessionId != null && id == currentSessionId),
        )
    }.sortedForDisplay()
