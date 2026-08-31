package uz.mahalla.feature.services.data

import kotlinx.serialization.SerializationException
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferForm
import uz.mahalla.feature.services.domain.ServiceOfferValidator
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceRequest
import uz.mahalla.feature.services.domain.ServiceRequestStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обе формы issue #71 за одним интерфейсом: заказ услуги у заведения и
 * анкета исполнителя.
 *
 * Кэша нет намеренно: заявка живёт минуты (мастер принимает или отклоняет её),
 * а анкету человек редактирует сам — показывать вчерашнюю копию хуже честной
 * ошибки.
 *
 * Интерфейс — ради тестов ViewModel: обе формы проверяются без MockWebServer.
 */
interface ServicesRepository {

    /** Заказать услугу в заведении [placeId] (`POST walkin/send`). */
    suspend fun sendServiceOrder(placeId: String, form: ServiceOrderForm): ApiResult<ServiceRequest>

    /**
     * Своя анкета исполнителя. `null` — анкеты ещё нет: это нормальный ответ
     * («вы пока не выставляли услуг»), а не ошибка, и форма открывается пустой.
     */
    suspend fun myOffer(): ApiResult<ServiceOffer?>

    suspend fun saveOffer(form: ServiceOfferForm): ApiResult<ServiceOffer>

    /** Переключить «принимаю заказы» (`PUT freelancers/me/toggle-availability`). */
    suspend fun toggleAvailability(): ApiResult<Unit>
}

@Singleton
class DefaultServicesRepository @Inject constructor(
    private val api: ServicesApi,
    private val phoneNumbers: PhoneNumberValidator,
) : ServicesRepository {

    /**
     * Разбор идёт **внутри** [apiCall]: заявка без `id` — тоже отказ, и
     * выброшенное мапперами исключение обязано превратиться в [ApiResult],
     * а не улететь в ViewModel.
     */
    override suspend fun sendServiceOrder(
        placeId: String,
        form: ServiceOrderForm,
    ): ApiResult<ServiceRequest> = apiCall {
        api.sendServiceOrder(
            WalkInRequestDto(
                placeId = placeId,
                userName = form.customerName.trim(),
                serviceName = form.serviceName.trim().takeIf(String::isNotEmpty),
            ),
        ).payload().toDomain()
    }

    /**
     * `404` — «анкеты нет». Отличить её от настоящей ошибки больше нечем:
     * отдельного «есть ли анкета» у бэкенда нет, а показать экран ошибки тому,
     * кто просто ещё не заполнял анкету, значит закрыть ему вход в форму
     * навсегда.
     */
    override suspend fun myOffer(): ApiResult<ServiceOffer?> {
        val result = apiCall { api.myOffer().payload().toDomain(phoneNumbers) }
        return when {
            result is ApiResult.Failure && result.error.isMissingOffer() -> ApiResult.Success(null)
            else -> result
        }
    }

    override suspend fun saveOffer(form: ServiceOfferForm): ApiResult<ServiceOffer> = apiCall {
        api.saveOffer(
            FreelancerRequestDto(
                name = form.name.trim(),
                profession = form.profession.trim(),
                city = form.city.trim(),
                bio = form.bio.trim().takeIf(String::isNotEmpty),
                phone = form.phoneDigits.trim().takeIf(String::isNotEmpty)
                    ?.let(phoneNumbers::toE164),
                hourlyRate = ServiceOfferValidator.rateSum(form),
                experienceYears = ServiceOfferValidator.experienceYears(form),
            ),
        ).payload().toDomain(phoneNumbers)
    }

    override suspend fun toggleAvailability(): ApiResult<Unit> =
        apiCall { api.toggleAvailability().ensureSuccess() }
}

/**
 * Анкеты нет: 404 от Spring либо тот же смысл кодом в конверте — бэкенд
 * отвечает и так, и так (`ApiError.Business` приходит из ответа 2xx с
 * `success:false`).
 */
private fun ApiError.isMissingOffer(): Boolean = when (this) {
    ApiError.NotFound -> true
    is ApiError.Business -> code?.contains("NOT_FOUND", ignoreCase = true) == true
    else -> false
}

/**
 * Заявка без `id` бесполезна: спросить её состояние потом будет нечем, а
 * молчаливое «отправлено» на несуществующей заявке — худший исход из всех.
 * Поэтому такой ответ считается отказом разбора, а не успехом.
 */
internal fun WalkInDto.toDomain(): ServiceRequest = ServiceRequest(
    id = id?.takeIf { it.isNotBlank() }
        ?: throw SerializationException("walk-in response without id"),
    placeId = placeId?.takeIf { it.isNotBlank() },
    userName = userName?.takeIf { it.isNotBlank() },
    serviceName = serviceName?.takeIf { it.isNotBlank() },
    barberNote = barberNote?.takeIf { it.isNotBlank() },
    status = ServiceRequestStatus.fromServer(status),
    // Ноль и отрицательные значения позицией в очереди не бывают: так сервер
    // отвечает, когда ещё её не считал.
    queuePosition = queuePosition?.takeIf { it > 0 },
    estimatedWaitMinutes = estimatedWaitMinutes?.takeIf { it > 0 },
    counterTime = counterTime?.takeIf { it.isNotBlank() },
    createdAt = parseServerInstant(createdAt),
)

internal fun FreelancerDto.toDomain(numbers: PhoneNumberValidator): ServiceOffer = ServiceOffer(
    id = id?.takeIf { it.isNotBlank() },
    name = name.orEmpty(),
    profession = profession.orEmpty(),
    bio = bio.orEmpty(),
    city = city.orEmpty(),
    // Номер приходит в E.164, а показывается и редактируется в привычном виде
    // `+998 90 123 45 67` — тем же форматтером, что и весь онбординг.
    phone = phone?.takeIf { it.isNotBlank() }
        ?.let { numbers.format(numbers.nationalDigits(it)) }
        .orEmpty(),
    hourlyRateSum = hourlyRate?.takeIf { it > 0 },
    experienceYears = experienceYears?.takeIf { it >= 0 },
    // Поля нет — считаем, что заказы принимаются: анкета создаётся именно
    // ради этого, а «скрыт» — состояние, которое человек включает сам.
    isAvailable = isAvailable ?: true,
    ratingAverage = ratingAverage?.takeIf { it > 0 },
    ratingCount = ratingCount ?: 0,
)
