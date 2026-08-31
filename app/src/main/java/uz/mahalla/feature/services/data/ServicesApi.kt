package uz.mahalla.feature.services.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import uz.mahalla.data.network.ApiResponse

/**
 * Услуги (issue #71): заказ услуги у заведения и анкета исполнителя.
 *
 * Контроллеров два, а фича одна — это две стороны одной сделки:
 *
 * | форма | эндпоинт |
 * |---|---|
 * | заказ услуги (клиент) | `POST walkin/send` |
 * | выставление услуги (исполнитель) | `GET`/`POST freelancers/me`, `PUT freelancers/me/toggle-availability` |
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl'ы). Все три ручки, кроме
 * каталога, требуют Bearer — без токена приходит `401 UNAUTHORIZED`, — поэтому
 * API создаётся на **основном** Retrofit, а не на «голом» `@RefreshClient`.
 * Ответы приезжают в общем конверте `{success, data, error}`.
 *
 * **Тело `POST freelancers/me` в схеме перекрыто**: springdoc свёл в один
 * `CreateRequest` анкету исполнителя и создание отзыва (та же коллизия, что
 * описана в `docs/UI-INVENTORY.md` §3.2), а проверить запросом нечем — ручка
 * анонимно отвечает 401. Поэтому тело собрано по `ProfileResponse`: те же
 * имена полей, минус вычисляемые сервером `id`, `userId`, `ratingAvg`,
 * `ratingCount`.
 */
interface ServicesApi {

    @POST("walkin/send")
    suspend fun sendServiceOrder(@Body body: WalkInRequestDto): ApiResponse<WalkInDto>

    @GET("freelancers/me")
    suspend fun myOffer(): ApiResponse<FreelancerDto>

    @POST("freelancers/me")
    suspend fun saveOffer(@Body body: FreelancerRequestDto): ApiResponse<FreelancerDto>

    /**
     * `ApiResponseVoid`: полезной нагрузки нет, `data` приезжает `null` и при
     * успехе. Тип — [JsonElement], как у отзыва сессии (issue #61): что бы
     * сервер туда ни положил, разбор не упадёт, а проверяется только `success`.
     */
    @PUT("freelancers/me/toggle-availability")
    suspend fun toggleAvailability(): ApiResponse<JsonElement>
}

/**
 * `SendRequest` контроллера `walk-in`. `serviceName` бэкенд объявляет
 * необязательным, но форма его требует — см. `ServiceOrderValidator`.
 */
@Serializable
data class WalkInRequestDto(
    @SerialName("placeId") val placeId: String,
    @SerialName("userName") val userName: String,
    @SerialName("serviceName") val serviceName: String? = null,
)

/**
 * `Response` контроллера `walk-in`. Все поля необязательные: отсутствие
 * позиции в очереди — обычное дело для только что отправленной заявки.
 */
@Serializable
data class WalkInDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("staffId") val staffId: String? = null,
    @SerialName("userName") val userName: String? = null,
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("barberNote") val barberNote: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("counterTime") val counterTime: String? = null,
    @SerialName("queuePosition") val queuePosition: Int? = null,
    @SerialName("estimatedWaitMinutes") val estimatedWaitMinutes: Int? = null,
    @SerialName("serviceStartedAt") val serviceStartedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `ProfileResponse` контроллера `freelancer`. */
@Serializable
data class FreelancerDto(
    @SerialName("id") val id: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("profession") val profession: String? = null,
    @SerialName("bio") val bio: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("hourlyRate") val hourlyRate: Long? = null,
    @SerialName("experienceYears") val experienceYears: Int? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("ratingAvg") val ratingAverage: Double? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
)

/**
 * Тело сохранения анкеты. Пустые поля не отправляются (`null`): «стереть
 * профессию» и «не менять её» для сервера должны выглядеть по-разному, а
 * пустая строка — ни то, ни другое.
 */
@Serializable
data class FreelancerRequestDto(
    @SerialName("name") val name: String,
    @SerialName("profession") val profession: String,
    @SerialName("city") val city: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("hourlyRate") val hourlyRate: Long? = null,
    @SerialName("experienceYears") val experienceYears: Int? = null,
)
