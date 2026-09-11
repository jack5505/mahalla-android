package uz.mahalla.feature.freelancer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Кабинет мастера (issue #190, часть эпика #16): анкета, свои услуги, входящие
 * заказы. Отдельный интерфейс от [FreelancerApi] намеренно — тот целиком про
 * клиента, который мастера ищет и заказывает у него, здесь же всё требует
 * Bearer и относится к «себе».
 *
 * Контракт **не подтверждён живым стендом**: `CONTRACT_REFRESH_TOKEN` не
 * задан ни в этом прогоне, ни на момент issue (см. риски #190). Пути и тела
 * собраны из того, что уже подтверждено для соседних ручек той же вертикали:
 *
 * - `GET/POST freelancers/me` отвечают схемой `ProfileResponse` /
 *   `FreelancerCreateRequest` — то же имя запроса встречается в
 *   `docs/UI-INVENTORY.md` (сверка #92) как разведённое от коллизии springdoc,
 *   но поля не перечислены; здесь они выведены из уже прочитанного
 *   `ProfileResponse` ([FreelancerDto]), как раньше делалось для
 *   `CreatePlaceRequest` (issue #84) и `CreateFreelancerOrderRequest`.
 * - `GET freelancers/me/orders` — та же страница заказов, что и
 *   `GET freelancers/orders/my` ([FreelancerOrderPageDto]), только с другой
 *   стороны: здесь входящие заказы **мастеру**, а не «мои заказы у мастеров».
 * - Своих услуг отдельной ручки чтения нет: список берётся тем же
 *   `GET freelancers/{id}/services`, что и в каталоге, но [id] — это `id` из
 *   ответа `me()`. [myServices] — свой метод с правильной схемой
 *   (`FreelancerServiceDto`), а не переиспользование [FreelancerApi.services]:
 *   там разбор чужой схемой — живой баг issue #216, трогать его не «заодно»,
 *   а отдельной задачей с тестами.
 * - `POST/PUT .../services` — тело `ServiceRequest` (единственная реально
 *   переиспользуемая схема сервиса по `docs/UI-INVENTORY.md`, п. 3.2), поля —
 *   те же, что у ответа `FreelancerServiceResponse`.
 * - `PUT .../toggle-availability` — без тела: в отличие от `places/{id}/availability`
 *   (issue #94) у мастера нет ни `lat`, ни `lng` в схеме [FreelancerDto],
 *   проверять по геопозиции бэкенду нечем.
 * - `PUT freelancers/orders/{orderId}/status` — тело со значением из того же
 *   перечисления, что и `OrderResponse.status` ([FreelancerOrderStatus]):
 *   `PENDING`, `ACCEPTED`, `REJECTED`, `COMPLETED`.
 *
 * Ничего из этого не проверено запросом под токеном: **до похода на стенд
 * считать черновиком**, а расхождение — в `docs/API-CONTRACT.md` и issue.
 */
interface FreelancerCabinetApi {

    /**
     * Своя анкета. Риск issue #190: без анкеты бэкенд может ответить и `404`,
     * и `200` с пустым `data` — из схемы не следует, какой из вариантов
     * настоящий. [FreelancerCabinetRepository.me] обрабатывает оба одинаково.
     */
    @GET("freelancers/me")
    suspend fun me(): ApiResponse<FreelancerDto>

    /**
     * Анкета мастера. Ручка одна на создание и на правку — отдельного `PUT`
     * бэкенд не отдаёт (см. таблицу эндпоинтов issue #190), поэтому кабинет
     * шлёт сюда же и первую анкету, и её последующую правку.
     */
    @POST("freelancers/me")
    suspend fun submitProfile(@Body body: FreelancerCreateRequest): ApiResponse<FreelancerDto>

    /** Входящие заказы мастеру, страницами. */
    @GET("freelancers/me/orders")
    suspend fun incomingOrders(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<FreelancerOrderPageDto>

    /**
     * Свои услуги. Путь — тот же каталожный `freelancers/{id}/services`, но
     * разбирается правильной схемой ([FreelancerServiceDto]), а не той, что
     * стоит в [FreelancerApi.services] (issue #216).
     */
    @GET("freelancers/{id}/services")
    suspend fun myServices(@Path("id") freelancerId: String): ApiResponse<List<FreelancerServiceDto>>

    @POST("freelancers/me/services")
    suspend fun addService(@Body body: FreelancerServiceRequest): ApiResponse<FreelancerServiceDto>

    @PUT("freelancers/me/services/{serviceId}")
    suspend fun updateService(
        @Path("serviceId") serviceId: String,
        @Body body: FreelancerServiceRequest,
    ): ApiResponse<FreelancerServiceDto>

    @DELETE("freelancers/me/services/{serviceId}")
    suspend fun deleteService(@Path("serviceId") serviceId: String): ApiResponse<JsonElement>

    /** Переключатель, как `places/{id}/availability` — желаемого значения в теле нет. */
    @PUT("freelancers/me/toggle-availability")
    suspend fun toggleAvailability(): ApiResponse<Boolean>

    @PUT("freelancers/orders/{orderId}/status")
    suspend fun updateOrderStatus(
        @Path("orderId") orderId: String,
        @Body body: UpdateFreelancerOrderStatusRequest,
    ): ApiResponse<FreelancerOrderDto>
}

/**
 * `FreelancerCreateRequest` (имя из схемы, `docs/UI-INVENTORY.md`, п. 3.2).
 * Поля выведены из подтверждённого `ProfileResponse` ([FreelancerDto]) —
 * живым запросом тело не проверить, `401` приходит до валидации на всех
 * ручках этой вертикали, требующих Bearer.
 *
 * [name] и [profession] обязательными сделаны на клиенте консервативно:
 * анкета без имени и специальности не отвечает на вопрос, кого и в чём искать
 * (та же пара показывается в каталоге). Остальное — как в ответе, всё
 * необязательно.
 *
 * [hourlyRate] — тийины, как в ответе (issue #149): то же имя поля, значит и
 * та же единица, пересчёт из сумов — в [DefaultFreelancerCabinetRepository].
 */
@Serializable
data class FreelancerCreateRequest(
    @SerialName("name") val name: String,
    @SerialName("profession") val profession: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("hourlyRate") val hourlyRate: Long? = null,
    @SerialName("experienceYears") val experienceYears: Int? = null,
)

/**
 * `FreelancerServiceResponse` — та же схема, что уже прочитана и
 * задокументирована в [FreelancerApi.services] (сверено 2026-09-10), но здесь
 * разобрана своим типом вместо чужого [uz.mahalla.feature.booking.data.ServiceDto].
 */
@Serializable
data class FreelancerServiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("freelancerId") val freelancerId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("priceAmount") val priceAmount: Long? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
)

/**
 * `ServiceRequest` — по `docs/UI-INVENTORY.md` (п. 3.2) это одна из пяти схем,
 * которые бэкенд правда переиспользует между путями, а не коллизия springdoc.
 * Поля зеркалят [FreelancerServiceDto]: `priceAmount` — тийины (issue #149,
 * пересчёт в [uz.mahalla.core.format.Money]), `title` и `priceAmount` с
 * `durationMinutes` обязательны — без них услугу нечем показать в каталоге и
 * нечем заказать.
 */
@Serializable
data class FreelancerServiceRequest(
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("priceAmount") val priceAmount: Long,
    @SerialName("durationMinutes") val durationMinutes: Int,
    @SerialName("isActive") val isActive: Boolean = true,
)

/**
 * Тело `PUT freelancers/orders/{orderId}/status`. Значение — то же
 * перечисление, что в `OrderResponse.status` ([FreelancerOrderStatus]);
 * своего значения выдумывать нельзя, шлём `apiValue` уже известных четырёх.
 */
@Serializable
data class UpdateFreelancerOrderStatusRequest(
    @SerialName("status") val status: String,
)
