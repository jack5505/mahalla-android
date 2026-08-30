package uz.mahalla.feature.profile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Активная сессия бэкенда (`ActiveSessionResponse`).
 *
 * @param currentDevice это устройство. Флаг ставит сам бэкенд по `deviceId` из
 * запроса; клиент дополнительно сверяет `sessionId` с сохранённой сессией —
 * ошибиться здесь значит предложить человеку отозвать вход, на котором он
 * сейчас работает.
 */
@Serializable
data class ActiveSessionDto(
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("lastActivityAt") val lastActivityAt: String? = null,
    @SerialName("lastIp") val lastIp: String? = null,
    @SerialName("trustedDevice") val trustedDevice: Boolean = false,
    @SerialName("currentDevice") val currentDevice: Boolean = false,
)

/**
 * @param revokeAll погасить все сессии, кроме текущей. Приложение шлёт
 * `false` — «выйти везде» это отдельное решение, а не побочный эффект отзыва
 * одного устройства. Значения по умолчанию у поля нет намеренно:
 * kotlinx.serialization такие поля из тела выбрасывает, и бэкенд получал бы
 * запрос без флага, полагаясь на собственный дефолт.
 */
@Serializable
data class RevokeSessionRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("revokeAll") val revokeAll: Boolean,
)

/**
 * Сессии устройств (контроллер `bank-auth`, issue #61).
 *
 * Запросы авторизованные — без токена все три отвечают `401 UNAUTHORIZED`
 * (проверено на стенде), поэтому API создаётся на **основном** клиенте с
 * `AuthInterceptor` и `TokenAuthenticator`, а не на «голом» `@RefreshClient`,
 * как остальная авторизация.
 */
interface SessionsApi {

    /**
     * `deviceId` и `platform` обязательны: по ним бэкенд помечает текущее
     * устройство в ответе.
     */
    @GET("auth/sessions")
    suspend fun sessions(
        @Query("deviceId") deviceId: String,
        @Query("platform") platform: String,
        @Query("osVersion") osVersion: String? = null,
    ): ApiResponse<List<ActiveSessionDto>>

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @POST("auth/sessions/revoke")
    suspend fun revoke(@Body body: RevokeSessionRequest): ApiResponse<JsonElement>

    @POST("auth/sessions/{sessionId}/trust")
    suspend fun trust(
        @Path("sessionId") sessionId: String,
        @Query("trusted") trusted: Boolean,
    ): ApiResponse<JsonElement>
}
