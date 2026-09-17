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
import uz.mahalla.feature.freelancer.domain.FreelancerProfileForm
import uz.mahalla.feature.freelancer.domain.FreelancerServiceForm

/**
 * Мастера в памяти (issue #107, кабинет мастера — issue #71): экраны
 * проверяются без MockWebServer.
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

    /** Входящие заказы (issue #190): ответ на страницу, иначе [defaultIncomingOrderPage]. */
    val incomingOrderPages: MutableMap<Int, ApiResult<FreelancerOrderPage>> = mutableMapOf()

    var defaultIncomingOrderPage: ApiResult<FreelancerOrderPage> =
        ApiResult.Success(FreelancerOrderPage())

    val requestedIncomingOrderPages = mutableListOf<Int>()

    var updateOrderStatusResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Пары «id заказа + новый статус», по порядку запросов. */
    val orderStatusChanges = mutableListOf<Pair<String, FreelancerOrderStatus>>()

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

    override suspend fun incomingOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> {
        requestedIncomingOrderPages += page
        return incomingOrderPages[page] ?: defaultIncomingOrderPage
    }

    override suspend fun updateOrderStatus(
        orderId: String,
        status: FreelancerOrderStatus,
    ): ApiResult<Unit> {
        orderStatusChanges += orderId to status
        return updateOrderStatusResult
    }

    // --- Кабинет мастера (issue #71) ---

    /** `Success(null)` — анкеты ещё нет: ровно то, что значит `404`. */
    var myProfileResult: ApiResult<Freelancer?> = ApiResult.Success(null)

    /** Ответы по порядку обращений, если их нужно различать (после правки). */
    val myProfileResults = mutableListOf<ApiResult<Freelancer?>>()

    var myProfileRequests = 0

    var myServicesResult: ApiResult<List<BarberService>> = ApiResult.Success(emptyList())

    val requestedMyServices = mutableListOf<String>()

    var saveProfileResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val savedProfiles = mutableListOf<FreelancerProfileForm>()

    var saveServiceResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val savedServices = mutableListOf<FreelancerServiceForm>()

    var deleteServiceResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val deletedServices = mutableListOf<String>()

    var toggleResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var toggleCount = 0

    override suspend fun myProfile(): ApiResult<Freelancer?> {
        val result = myProfileResults.getOrNull(myProfileRequests) ?: myProfileResult
        myProfileRequests++
        return result
    }

    override suspend fun saveMyProfile(form: FreelancerProfileForm): ApiResult<Unit> {
        savedProfiles += form
        return saveProfileResult
    }

    override suspend fun myServices(freelancerId: String): ApiResult<List<BarberService>> {
        requestedMyServices += freelancerId
        return myServicesResult
    }

    override suspend fun saveMyService(form: FreelancerServiceForm): ApiResult<Unit> {
        savedServices += form
        return saveServiceResult
    }

    override suspend fun deleteMyService(serviceId: String): ApiResult<Unit> {
        deletedServices += serviceId
        return deleteServiceResult
    }

    override suspend fun toggleAvailability(): ApiResult<Unit> {
        toggleCount++
        return toggleResult
    }
}
