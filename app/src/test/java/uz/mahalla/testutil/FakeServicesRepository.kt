package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.services.data.ServicesRepository
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferForm
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceRequest

/**
 * Услуги в памяти (issue #71): обе формы проверяются без MockWebServer.
 *
 * Тела запросов сохраняются — так видно, что на сервер уходит именно то, что
 * набрали в форме, а не её исходное состояние.
 */
class FakeServicesRepository : ServicesRepository {

    var orderResult: ApiResult<ServiceRequest> =
        ApiResult.Success(ServiceRequest(id = "r-1"))

    var offerResult: ApiResult<ServiceOffer?> = ApiResult.Success(null)

    var saveResult: ApiResult<ServiceOffer> = ApiResult.Success(ServiceOffer())

    var toggleResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val sentOrders = mutableListOf<Pair<String, ServiceOrderForm>>()
    val savedOffers = mutableListOf<ServiceOfferForm>()

    var offerCount: Int = 0
        private set
    var toggleCount: Int = 0
        private set

    override suspend fun sendServiceOrder(
        placeId: String,
        form: ServiceOrderForm,
    ): ApiResult<ServiceRequest> {
        sentOrders += placeId to form
        return orderResult
    }

    override suspend fun myOffer(): ApiResult<ServiceOffer?> {
        offerCount++
        return offerResult
    }

    override suspend fun saveOffer(form: ServiceOfferForm): ApiResult<ServiceOffer> {
        savedOffers += form
        return saveResult
    }

    override suspend fun toggleAvailability(): ApiResult<Unit> {
        toggleCount++
        return toggleResult
    }
}
