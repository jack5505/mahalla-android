package uz.mahalla.data.network.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import uz.mahalla.data.network.ApiResponse

/**
 * Жива ли сессия и требует ли она PIN прямо сейчас (`CheckSessionRequest`).
 *
 * Координат здесь нет — в отличие от всех остальных запросов `bank-auth`:
 * схема требует только устройство.
 */
@Serializable
data class CheckSessionRequest(
    @SerialName("device") val device: DeviceInfoDto,
)

/**
 * @param sessionValid сессия ещё существует. `false` — дальше не PIN, а
 * повторный вход: экран блокировки, который никогда не откроется, это тупик.
 * @param pinRequired сервер хочет, чтобы сессию подтвердили PIN'ом
 * (`auth/pin-resume`).
 * @param reason машинная причина отказа — показывать её человеку нечем, но в
 * отчёте о падении и в инспекторе трафика она объясняет остальное.
 */
@Serializable
data class CheckSessionResponse(
    @SerialName("sessionValid") val sessionValid: Boolean? = null,
    @SerialName("pinRequired") val pinRequired: Boolean? = null,
    @SerialName("user") val user: UserDto? = null,
    @SerialName("reason") val reason: String? = null,
)

/**
 * Продолжить сессию после блокировки: PIN в обмен на свежую пару токенов.
 *
 * От `auth/pin-login` отличается тем, что это **не вход**: сессия уже есть, и
 * запрос требует Bearer. Поэтому чужой аккаунт здесь приехать не может — в
 * отличие от `pin-login`, который ищет пользователя по устройству (issue #86).
 */
@Serializable
data class PinResumeRequest(
    @SerialName("pin") val pin: String,
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/**
 * App-lock со стороны бэкенда (контроллер `bank-auth`, issue #102).
 *
 * Обе ручки **авторизованные** — без токена отвечают `401 UNAUTHORIZED`
 * (проверено на стенде). Этим они отличаются от `auth/pin-login` и
 * `auth/setup-pin`, которые анонимны, и поэтому живут не в [AuthApi] на
 * «голом» `@RefreshClient`, а здесь — на **основном** Retrofit, с
 * `AuthInterceptor` и `TokenAuthenticator`.
 */
interface SessionApi {

    @POST("auth/session/check")
    suspend fun checkSession(@Body body: CheckSessionRequest): ApiResponse<CheckSessionResponse>

    @POST("auth/pin-resume")
    suspend fun pinResume(@Body body: PinResumeRequest): ApiResponse<AuthResponseDto>
}
