package uz.mahalla.feature.freelancer.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.booking.data.ServiceDto
import uz.mahalla.feature.booking.data.toDomain
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerPage
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вертикаль «Мастера» (issue #107): каталог, профиль, услуги, заказ, свои
 * заказы.
 *
 * Кэша нет намеренно — ни у каталога, ни у услуг, ни у заказов. Мастер
 * выключает доступность одним переключателем
 * (`PUT freelancers/me/toggle-availability`), состав услуг меняет он же, а
 * статус заказа — тем более (`PUT freelancers/orders/{orderId}/status`):
 * `PENDING` из Room после того, как мастер уже отказался, был бы прямой ложью.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface FreelancerRepository {

    /**
     * Каталог мастеров.
     *
     * @param profession фильтр по специальности; пустая строка означает «без
     * фильтра» и в запрос не уходит вовсе.
     */
    suspend fun freelancers(
        profession: String? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<FreelancerPage>

    /** Профиль мастера. */
    suspend fun freelancer(freelancerId: String): ApiResult<Freelancer>

    /**
     * Услуги мастера. Выключенные (`isActive: false`) в список не попадают:
     * заказать их нельзя, а строка, которая ничего не делает, читается как
     * сломанная (то же правило, что в брони, issue #97).
     */
    suspend fun services(freelancerId: String): ApiResult<List<BarberService>>

    /** Заказать услугу. Черновик проверяется до запроса. */
    suspend fun order(
        freelancerId: String,
        draft: FreelancerOrderDraft,
    ): ApiResult<FreelancerOrder>

    /** Свои заказы у мастеров, страницами. */
    suspend fun myOrders(
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<FreelancerOrderPage>

    companion object {
        /** Код отказа, когда заказывать нечего ещё до запроса. */
        const val INVALID_REQUEST_CODE = "FREELANCER_ORDER_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultFreelancerRepository @Inject constructor(
    private val api: FreelancerApi,
    private val clock: Clock,
) : FreelancerRepository {

    override suspend fun freelancers(
        profession: String?,
        page: Int,
        size: Int,
    ): ApiResult<FreelancerPage> = apiCall {
        api.freelancers(
            // Пустой фильтр — это отсутствие параметра, а не `profession=`:
            // пустую строку бэкенд вправе счесть искомой специальностью.
            profession = profession?.trim()?.takeIf(String::isNotEmpty),
            page = page.coerceAtLeast(0),
            size = size,
        ).payload()
    }.map(FreelancerPageDto::toDomain)

    /**
     * Профиль. Ответ **без `id`** — отказ разбора: заказывать у мастера,
     * которого нечем назвать в пути запроса, невозможно, и лучше сказать об
     * этом сразу, чем показать экран с кнопкой, которая не сработает.
     */
    override suspend fun freelancer(freelancerId: String): ApiResult<Freelancer> = apiCall {
        api.freelancer(freelancerId).payload()
    }.let { result ->
        when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.data.toDomain()
                ?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Serialization)
        }
    }

    override suspend fun services(freelancerId: String): ApiResult<List<BarberService>> =
        apiCall { api.services(freelancerId).payload() }
            .map { services -> services.mapNotNull(ServiceDto::toDomain).filter { it.isActive } }

    /**
     * Заказ.
     *
     * Незаполненный черновик и прошедшее время в сеть не уходят: сервер ответил
     * бы тем же отказом, но платой были бы запрос и молчание экрана на время
     * его выполнения. Прошедшее время проверяется только когда его выбрали —
     * заказ «как можно скорее» идёт вообще без `scheduledAt`.
     *
     * Ответ без `id` отказом **не** считается — заказ создан, а увидеть его
     * можно в «моих заказах» (см. [toCreated]).
     */
    override suspend fun order(
        freelancerId: String,
        draft: FreelancerOrderDraft,
    ): ApiResult<FreelancerOrder> {
        val serviceId = draft.serviceId
        val scheduledAt = draft.scheduledAt()
        if (freelancerId.isBlank() || serviceId.isNullOrBlank() || !draft.canSubmit) {
            return ApiResult.Failure(
                ApiError.Business(FreelancerRepository.INVALID_REQUEST_CODE),
            )
        }
        if (scheduledAt != null && scheduledAt.isBefore(clock.instant())) {
            return ApiResult.Failure(
                ApiError.Business(FreelancerRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            api.createOrder(
                freelancerId = freelancerId,
                body = CreateFreelancerOrderRequest(
                    serviceId = serviceId,
                    // ISO-8601 с зоной: `Instant.toString()` даёт ровно его.
                    scheduledAt = scheduledAt?.toString(),
                    address = draft.addressOrNull(),
                    comment = draft.commentOrNull(),
                ),
            ).payload()
        }.map(FreelancerOrderDto::toCreated)
    }

    override suspend fun myOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> =
        apiCall { api.myOrders(page = page.coerceAtLeast(0), size = size).payload() }
            .map(FreelancerOrderPageDto::toDomain)
}
