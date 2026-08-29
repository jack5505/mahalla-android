package uz.mahalla.data.network.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Устройство, с которого идёт вход. Бэкенд заводит на него сессию, поэтому
 * `deviceId` и `platform` обязательны (issue #42).
 */
@Serializable
data class DeviceInfoDto(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("platform") val platform: String,
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("osVersion") val osVersion: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
    @SerialName("fcmToken") val fcmToken: String? = null,
)

/**
 * Запрос кода из SMS. Кроме номера бэкенд требует устройство и координаты:
 * без них ответ — 400 `VALIDATION_ERROR` («Joylashuv ruxsatini yoqing»).
 */
@Serializable
data class SendOtpRequest(
    @SerialName("phone") val phone: String,
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/**
 * @param otpToken связывает отправленный код с последующей верификацией:
 * `verify-otp` принимает именно его, а не номер телефона.
 * @param cooldownSeconds через сколько можно просить код повторно.
 * @param channel `SMS` или `TELEGRAM`.
 */
@Serializable
data class SendOtpResponse(
    @SerialName("otpToken") val otpToken: String? = null,
    @SerialName("expiresInSeconds") val expiresInSeconds: Int? = null,
    @SerialName("cooldownSeconds") val cooldownSeconds: Int? = null,
    @SerialName("maskedPhone") val maskedPhone: String? = null,
    @SerialName("channel") val channel: String? = null,
)

@Serializable
data class VerifyOtpRequest(
    @SerialName("otpToken") val otpToken: String,
    @SerialName("otpCode") val otpCode: String,
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/**
 * @param accessExpiresIn срок жизни access-токена в секундах.
 */
@Serializable
data class TokenPairDto(
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("accessExpiresIn") val accessExpiresIn: Long? = null,
    @SerialName("refreshExpiresIn") val refreshExpiresIn: Long? = null,
)

@Serializable
data class UserDto(
    @SerialName("id") val id: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("fullName") val fullName: String? = null,
    @SerialName("avatarUrl") val avatarUrl: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("verificationStatus") val verificationStatus: String? = null,
    @SerialName("accountStatus") val accountStatus: String? = null,
    @SerialName("pinSetup") val pinSetup: Boolean = false,
)

/**
 * @param nextStep что бэкенд предлагает делать дальше: `SETUP_PIN`,
 * `ENTER_PIN` или `NONE`. Приложение держит PIN локально (эпик 3.4), поэтому
 * поле пока только доезжает до домена.
 */
@Serializable
data class VerifyOtpResponse(
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("nextStep") val nextStep: String? = null,
    @SerialName("tokens") val tokens: TokenPairDto? = null,
    @SerialName("user") val user: UserDto? = null,
)

/**
 * Начало входа через Telegram (issue #46). Номера телефона здесь нет: кто
 * именно входит, бэкенд узнаёт от бота, когда пользователь нажмёт Start.
 */
@Serializable
data class TelegramInitRequest(
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/**
 * @param telegramBotUrl `https://t.me/<bot>?start=<deepLinkToken>` — ссылку
 * проверяет `TelegramBotLink` до открытия.
 * @param expiresInSeconds сколько живёт токен (на стенде — 300).
 */
@Serializable
data class TelegramInitResponse(
    @SerialName("deepLinkToken") val deepLinkToken: String? = null,
    @SerialName("telegramBotUrl") val telegramBotUrl: String? = null,
    @SerialName("expiresInSeconds") val expiresInSeconds: Int? = null,
)

@Serializable
data class TelegramCheckRequest(
    @SerialName("deepLinkToken") val deepLinkToken: String,
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/**
 * Ответ на подтверждённый вход через Telegram.
 *
 * Токены лежат в корне, а не в `tokens`, как у `verify-otp`, и `sessionId`
 * бэкенд не отдаёт вовсе — значит `X-Session-Id` при выходе будет пустым
 * (`logout` это допускает).
 *
 * @param requiresPhoneVerify телефон аккаунта не подтверждён: вход придётся
 * добить обычным SMS-кодом.
 */
@Serializable
data class TelegramCheckResponse(
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("accessExpiresIn") val accessExpiresIn: Long? = null,
    @SerialName("refreshExpiresIn") val refreshExpiresIn: Long? = null,
    @SerialName("requiresPhoneVerify") val requiresPhoneVerify: Boolean = false,
    @SerialName("user") val user: UserDto? = null,
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("device") val device: DeviceInfoDto,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

@Serializable
data class AuthResponseDto(
    @SerialName("tokens") val tokens: TokenPairDto? = null,
    @SerialName("user") val user: UserDto? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("pinSetup") val pinSetup: Boolean = false,
    @SerialName("canMarkTrusted") val canMarkTrusted: Boolean = false,
)

/**
 * Авторизация по SMS-коду и обновление токенов (контроллер `bank-auth` на
 * бэкенде, префикс пути — `api/v1/auth`, он уже входит в `API_BASE_URL`).
 *
 * Все ответы приходят в конверте [ApiResponse] — полезная нагрузка лежит в
 * `data`, причина отказа в `error`. Разворачивает его `payload()`.
 *
 * Все методы ходят через отдельный клиент (`@RefreshClient`) — без
 * авторизационного интерсептора и без `Authenticator`:
 *  - запрос и проверка кода анонимны по определению;
 *  - `refresh` иначе получил бы рекурсию (401 на refresh → снова refresh);
 *  - `logout` должен уходить и с уже мёртвым access-токеном, а 401 на нём не
 *    должен запускать обновление.
 */
interface AuthApi {
    @POST("auth/send-otp")
    suspend fun sendOtp(@Body body: SendOtpRequest): ApiResponse<SendOtpResponse>

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): ApiResponse<VerifyOtpResponse>

    /**
     * Выдать одноразовую ссылку на бота (issue #46). Анонимный запрос —
     * подтверждать личность будет сам Telegram.
     */
    @POST("auth/telegram/init")
    suspend fun telegramInit(@Body body: TelegramInitRequest): ApiResponse<TelegramInitResponse>

    /**
     * Нажали ли Start. Пока не нажали — 400 с кодом `TG_PENDING`; это штатное
     * ожидание, а не отказ (разбирает `DefaultAuthRepository`).
     */
    @POST("auth/telegram/check")
    suspend fun telegramCheck(@Body body: TelegramCheckRequest): ApiResponse<TelegramCheckResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): ApiResponse<AuthResponseDto>

    /**
     * Выход. Тела у запроса нет: сессию бэкенд определяет по заголовку
     * `X-Session-Id` (его отдал `verify-otp`), а `allDevices` оставляем
     * выключенным — выходим только с этого устройства.
     */
    @POST("auth/logout")
    suspend fun logout(
        @Header("X-Session-Id") sessionId: String?,
        @Query("allDevices") allDevices: Boolean,
    ): ApiResponse<JsonElement>
}
