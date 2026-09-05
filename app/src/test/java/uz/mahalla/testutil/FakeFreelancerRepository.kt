package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerPage

/**
 * Мастера в памяти (issue #107): экраны проверяются без MockWebServer.
 *
 * Ответ на каждую страницу задаётся отдельно — иначе не отличить догрузку от
 * повторной загрузки первой страницы.
 */
class FakeFreelancerRepository : FreelancerRepository {

    /** Каталог: ответ на страницу, иначе [defaultCatalogPage]. */
    val catalogPages: MutableMap<Int, ApiResult<FreelancerPage>> = mutableMapOf()

    var defaultCatalogPage: ApiResult<FreelancerPage> = ApiResult.Success(FreelancerPage())

    /** Пары «страница + фильтр», по порядку запросов. */
    val catalogRequests = mutableListOf<Pair<Int, String?>>()

    var profileResult: ApiResult<Freelancer>? = null

    val requestedProfiles = mutableListOf<String>()

    var servicesResult: ApiResult<List<BarberService>> = ApiResult.Success(emptyList())

    val requestedServices = mutableListOf<String>()

    var orderResult: ApiResult<FreelancerOrder>? = null

    /** Черновики, ушедшие в `order`, вместе с мастером. */
    val orders = mutableListOf<Pair<String, FreelancerOrderDraft>>()

    val myOrderPages: MutableMap<Int, ApiResult<FreelancerOrderPage>> = mutableMapOf()

    var defaultMyOrderPage: ApiResult<FreelancerOrderPage> =
        ApiResult.Success(FreelancerOrderPage())

    val requestedMyOrderPages = mutableListOf<Int>()

    override suspend fun freelancers(
        profession: String?,
        page: Int,
        size: Int,
    ): ApiResult<FreelancerPage> {
        catalogRequests += page to profession
        return catalogPages[page] ?: defaultCatalogPage
    }

    override suspend fun freelancer(freelancerId: String): ApiResult<Freelancer> {
        requestedProfiles += freelancerId
        return profileResult ?: ApiResult.Success(Freelancer(id = freelancerId, name = "Usta"))
    }

    override suspend fun services(freelancerId: String): ApiResult<List<BarberService>> {
        requestedServices += freelancerId
        return servicesResult
    }

    override suspend fun order(
        freelancerId: String,
        draft: FreelancerOrderDraft,
    ): ApiResult<FreelancerOrder> {
        orders += freelancerId to draft
        return orderResult ?: ApiResult.Success(
            FreelancerOrder(
                id = "o-1",
                freelancerId = freelancerId,
                serviceId = draft.serviceId,
                status = FreelancerOrderStatus.Pending,
                scheduledAt = draft.scheduledAt(),
                address = draft.addressOrNull(),
                comment = draft.commentOrNull(),
            ),
        )
    }

    override suspend fun myOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> {
        requestedMyOrderPages += page
        return myOrderPages[page] ?: defaultMyOrderPage
    }
}
