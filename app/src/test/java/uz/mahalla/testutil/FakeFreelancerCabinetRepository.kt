package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.freelancer.data.FreelancerCabinetRepository
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft

/** Кабинет мастера в памяти (issue #190): экраны проверяются без MockWebServer. */
class FakeFreelancerCabinetRepository : FreelancerCabinetRepository {

    var meResult: ApiResult<Freelancer?> = ApiResult.Success(null)
    var meCallCount = 0

    var submitProfileResult: ApiResult<Freelancer>? = null
    val submittedForms = mutableListOf<FreelancerAnketaForm>()

    val incomingOrderPages: MutableMap<Int, ApiResult<FreelancerOrderPage>> = mutableMapOf()
    var defaultIncomingOrderPage: ApiResult<FreelancerOrderPage> = ApiResult.Success(FreelancerOrderPage())
    val requestedOrderPages = mutableListOf<Int>()

    var servicesResult: ApiResult<List<FreelancerCabinetService>> = ApiResult.Success(emptyList())
    val requestedServicesFor = mutableListOf<String>()

    var addServiceResult: ApiResult<FreelancerCabinetService>? = null
    val addedServices = mutableListOf<FreelancerServiceDraft>()

    var updateServiceResult: ApiResult<FreelancerCabinetService>? = null
    val updatedServices = mutableListOf<Pair<String, FreelancerServiceDraft>>()

    var deleteServiceResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val deletedServiceIds = mutableListOf<String>()

    var toggleAvailabilityResult: ApiResult<Boolean>? = null

    var updateOrderStatusResult: ApiResult<FreelancerOrder>? = null
    val orderStatusUpdates = mutableListOf<Pair<String, FreelancerOrderStatus>>()

    override suspend fun me(): ApiResult<Freelancer?> {
        meCallCount += 1
        return meResult
    }

    override suspend fun submitProfile(form: FreelancerAnketaForm): ApiResult<Freelancer> {
        submittedForms += form
        return submitProfileResult ?: ApiResult.Success(
            Freelancer(id = "f-1", name = form.name, profession = form.profession),
        )
    }

    override suspend fun incomingOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> {
        requestedOrderPages += page
        return incomingOrderPages[page] ?: defaultIncomingOrderPage
    }

    override suspend fun services(freelancerId: String): ApiResult<List<FreelancerCabinetService>> {
        requestedServicesFor += freelancerId
        return servicesResult
    }

    override suspend fun addService(draft: FreelancerServiceDraft): ApiResult<FreelancerCabinetService> {
        addedServices += draft
        return addServiceResult ?: ApiResult.Success(
            FreelancerCabinetService(
                id = "s-new",
                title = draft.trimmedTitle,
                priceSum = draft.priceSum ?: 0,
                durationMinutes = draft.durationMinutes ?: 0,
                isActive = draft.isActive,
            ),
        )
    }

    override suspend fun updateService(
        serviceId: String,
        draft: FreelancerServiceDraft,
    ): ApiResult<FreelancerCabinetService> {
        updatedServices += serviceId to draft
        return updateServiceResult ?: ApiResult.Success(
            FreelancerCabinetService(
                id = serviceId,
                title = draft.trimmedTitle,
                priceSum = draft.priceSum ?: 0,
                durationMinutes = draft.durationMinutes ?: 0,
                isActive = draft.isActive,
            ),
        )
    }

    override suspend fun deleteService(serviceId: String): ApiResult<Unit> {
        deletedServiceIds += serviceId
        return deleteServiceResult
    }

    override suspend fun toggleAvailability(current: Boolean): ApiResult<Boolean> =
        toggleAvailabilityResult ?: ApiResult.Success(!current)

    override suspend fun updateOrderStatus(
        orderId: String,
        status: FreelancerOrderStatus,
    ): ApiResult<FreelancerOrder> {
        orderStatusUpdates += orderId to status
        return updateOrderStatusResult ?: ApiResult.Success(
            FreelancerOrder(id = orderId, status = status),
        )
    }
}
