package uz.mahalla.feature.freelancer.data

import uz.mahalla.core.format.Money
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaFormValidator
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormValidator
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кабинет мастера (issue #190): своя анкета, свои услуги, входящие заказы.
 *
 * Кэша нет нигде в этом репозитории — та же причина, что у [FreelancerRepository]:
 * доступность, состав услуг и статус заказа меняет сам мастер или клиент
 * заново на сервере, и любая локальная копия быстро соврёт.
 *
 * Интерфейс — ради тестов ViewModel без MockWebServer.
 */
interface FreelancerCabinetRepository {

    /**
     * Своя анкета. `null` — анкеты ещё нет, и это **не отказ**: риск issue
     * #190 в том, что бэкенд может ответить и `404`, и `200` с пустым `data`,
     * — здесь оба варианта приводят к одному и тому же `null`, чтобы экран не
     * зависел от того, какой из них окажется настоящим.
     */
    suspend fun me(): ApiResult<Freelancer?>

    /** Анкета создаётся и правится одной и той же ручкой (issue #190). */
    suspend fun submitProfile(form: FreelancerAnketaForm): ApiResult<Freelancer>

    /** Входящие заказы, страницами. */
    suspend fun incomingOrders(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<FreelancerOrderPage>

    /** Свои услуги — активные и выключенные, мастер должен видеть все. */
    suspend fun services(freelancerId: String): ApiResult<List<FreelancerCabinetService>>

    suspend fun addService(draft: FreelancerServiceDraft): ApiResult<FreelancerCabinetService>

    suspend fun updateService(
        serviceId: String,
        draft: FreelancerServiceDraft,
    ): ApiResult<FreelancerCabinetService>

    suspend fun deleteService(serviceId: String): ApiResult<Unit>

    /**
     * Переключить «принимаю заказы».
     *
     * @param current известное приложению состояние — ручка переключатель, и
     * желаемого значения в теле нет (то же правило, что у `places/{id}/availability`,
     * issue #94): молчание сервера о новом значении читается как «флаг
     * перевернулся».
     */
    suspend fun toggleAvailability(current: Boolean): ApiResult<Boolean>

    suspend fun updateOrderStatus(
        orderId: String,
        status: FreelancerOrderStatus,
    ): ApiResult<FreelancerOrder>

    companion object {
        /** Код отказа, когда анкета или услуга не проходят проверку ещё на клиенте. */
        const val INVALID_FORM_CODE = "FREELANCER_CABINET_FORM_INVALID"

        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultFreelancerCabinetRepository @Inject constructor(
    private val api: FreelancerCabinetApi,
    private val phoneValidator: PhoneNumberValidator,
) : FreelancerCabinetRepository {

    override suspend fun me(): ApiResult<Freelancer?> = when (val result = apiCall { api.me() }) {
        is ApiResult.Success -> ApiResult.Success(result.data.data?.toDomain())
        is ApiResult.Failure -> if (result.error == ApiError.NotFound) {
            ApiResult.Success(null)
        } else {
            result
        }
    }

    override suspend fun submitProfile(form: FreelancerAnketaForm): ApiResult<Freelancer> {
        val trimmed = form.trimmed()
        val errors = FreelancerAnketaFormValidator.validate(trimmed, phoneValidator::isValid)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(
                ApiError.Business(FreelancerCabinetRepository.INVALID_FORM_CODE),
            )
        }

        return apiCall {
            api.submitProfile(
                FreelancerCreateRequest(
                    name = trimmed.name,
                    profession = trimmed.profession,
                    bio = trimmed.bio.takeIf(String::isNotEmpty),
                    city = trimmed.city.takeIf(String::isNotEmpty),
                    phone = trimmed.phoneDigits.takeIf(String::isNotEmpty)
                        ?.let(phoneValidator::toE164),
                    // `hourlyRate` в ответе — тийины (issue #149); в запросе
                    // то же имя поля, и пересчёт должен быть в ту же сторону.
                    hourlyRate = trimmed.hourlyRateSum?.let(Money::somToTiyin),
                    experienceYears = trimmed.experienceYears,
                ),
            ).payload()
        }.let { result ->
            when (result) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> result.data.toDomain()
                    ?.let { ApiResult.Success(it) }
                    ?: ApiResult.Failure(ApiError.Serialization)
            }
        }
    }

    override suspend fun incomingOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> =
        apiCall { api.incomingOrders(page = page.coerceAtLeast(0), size = size).payload() }
            .map(FreelancerOrderPageDto::toDomain)

    override suspend fun services(freelancerId: String): ApiResult<List<FreelancerCabinetService>> =
        apiCall { api.myServices(freelancerId).payload() }
            .map { services -> services.mapNotNull(FreelancerServiceDto::toDomain) }

    override suspend fun addService(draft: FreelancerServiceDraft): ApiResult<FreelancerCabinetService> {
        val errors = FreelancerServiceFormValidator.validate(draft)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(FreelancerCabinetRepository.INVALID_FORM_CODE))
        }
        return apiCall { api.addService(draft.toRequest()).payload() }
            .let { result ->
                when (result) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> result.data.toDomain()
                        ?.let { ApiResult.Success(it) }
                        ?: ApiResult.Failure(ApiError.Serialization)
                }
            }
    }

    override suspend fun updateService(
        serviceId: String,
        draft: FreelancerServiceDraft,
    ): ApiResult<FreelancerCabinetService> {
        val errors = FreelancerServiceFormValidator.validate(draft)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(FreelancerCabinetRepository.INVALID_FORM_CODE))
        }
        return apiCall { api.updateService(serviceId, draft.toRequest()).payload() }
            .let { result ->
                when (result) {
                    is ApiResult.Failure -> result
                    is ApiResult.Success -> result.data.toDomain()
                        ?.let { ApiResult.Success(it) }
                        ?: ApiResult.Failure(ApiError.Serialization)
                }
            }
    }

    override suspend fun deleteService(serviceId: String): ApiResult<Unit> = apiCall {
        api.deleteService(serviceId).ensureSuccess()
    }

    override suspend fun toggleAvailability(current: Boolean): ApiResult<Boolean> = apiCall {
        val response = api.toggleAvailability()
        // `ensureSuccess`, а не `payload`: `data` тут `Boolean`, и `false` —
        // законный ответ («выключил приём заказов»), который `payload` от
        // отсутствия значения не отличает (то же правило, что у заведений,
        // issue #94).
        response.ensureSuccess()
        response.data ?: !current
    }

    override suspend fun updateOrderStatus(
        orderId: String,
        status: FreelancerOrderStatus,
    ): ApiResult<FreelancerOrder> = apiCall {
        api.updateOrderStatus(orderId, UpdateFreelancerOrderStatusRequest(status.apiValue)).payload()
    }.let { result ->
        when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.data.toDomain()
                ?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Serialization)
        }
    }
}
