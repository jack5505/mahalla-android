package uz.mahalla.data.network.pin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Что бэкенд знает о PIN этого устройства (`PinStatusResponse`).
 *
 * Все поля необязательные — как и везде в этой схеме. `null` у флага читается
 * как «нет»: спрятать смену PIN из-за неприехавшего поля хуже, чем показать
 * её и получить внятный отказ сервера.
 *
 * @param lockedSecondsRemaining сколько осталось до конца блокировки после
 * исчерпанных попыток. Считает её сервер — свой счётчик приложение не ведёт
 * (issue #51).
 */
@Serializable
data class PinStatusDto(
    @SerialName("pinSet") val pinSet: Boolean? = null,
    @SerialName("biometricEnabled") val biometricEnabled: Boolean? = null,
    @SerialName("lockedSecondsRemaining") val lockedSecondsRemaining: Long? = null,
    @SerialName("pinChangedAt") val pinChangedAt: String? = null,
    @SerialName("lastUsedAt") val lastUsedAt: String? = null,
)

/** Оба кода — ровно шесть цифр (`^[0-9]{6}$`), это требование бэкенда. */
@Serializable
data class ChangePinRequest(
    @SerialName("currentPin") val currentPin: String,
    @SerialName("newPin") val newPin: String,
    @SerialName("deviceId") val deviceId: String,
)

/**
 * Переключатель биометрии на сервере.
 *
 * PIN здесь обязателен по схеме: включение входа по отпечатку — это смена
 * настройки безопасности, и подтверждать её бэкенд требует тем же кодом.
 * Значения по умолчанию у [enabled] нет намеренно: kotlinx.serialization
 * выбрасывает из тела поля, равные дефолту, и выключение уходило бы запросом
 * без флага (та же грабля, что у `RevokeSessionRequest`, issue #61).
 */
@Serializable
data class BiometricToggleRequest(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("pin") val pin: String,
)

/**
 * Аккаунтный PIN (контроллер `pin-code-controller`, issue #102).
 *
 * Все ручки авторизованные — без токена отвечают `401 UNAUTHORIZED`
 * (проверено на стенде), поэтому API создаётся на **основном** Retrofit с
 * `AuthInterceptor` и `TokenAuthenticator`, а не на «голом» `@RefreshClient`,
 * где живёт остальная авторизация.
 *
 * Три из семи ручек контроллера сюда не попали, и это осознанно:
 *  - `POST pin/set` и `POST pin/reset` требуют пары `otpToken` + `otpCode`,
 *    то есть свежего SMS-кода. Установку PIN приложение уже делает через
 *    `auth/setup-pin` на входе (issue #51), а «сброс по SMS» у него — это
 *    выход и вход заново: тот путь короче и не требует второго кода.
 *  - `DELETE pin` удаляет PIN, оставляя сессию живой. Приложение без PIN не
 *    умеет запирать себя вовсе (app-lock отключился бы молча), поэтому
 *    отдельной кнопки «удалить PIN» нет — есть выход из аккаунта.
 */
interface PinApi {

    /**
     * `deviceId` обязателен: состояние PIN бэкенд ведёт по устройству, как и
     * вход (`pin-login` ищет пользователя по нему же).
     */
    @GET("pin/status")
    suspend fun status(@Query("deviceId") deviceId: String): ApiResponse<PinStatusDto>

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @PUT("pin/change")
    suspend fun change(@Body body: ChangePinRequest): ApiResponse<JsonElement>

    /** `data` — новое состояние флага; ответ без него не отказ (см. репозиторий). */
    @PUT("pin/biometric")
    suspend fun setBiometric(@Body body: BiometricToggleRequest): ApiResponse<Boolean>
}
