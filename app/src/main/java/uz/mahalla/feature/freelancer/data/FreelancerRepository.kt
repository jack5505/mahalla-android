package uz.mahalla.feature.freelancer.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerPage
import uz.mahalla.feature.freelancer.domain.FreelancerProfileForm
import uz.mahalla.feature.freelancer.domain.FreelancerProfileFormValidator
import uz.mahalla.feature.freelancer.domain.FreelancerServiceForm
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormValidator
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вертикаль «Мастера»: каталог, профиль, услуги, заказ, свои заказы
 * (issue #107) и кабинет самого мастера — анкета и выставление услуг
 * (issue #71).
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

    /**
     * Входящие заказы мастера (issue #190) — то, что клиенты заказали у
     * **этого** мастера, а не то, что он сам заказал ([myOrders]).
     */
    suspend fun incomingOrders(
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<FreelancerOrderPage>

    /**
     * Сменить статус входящего заказа — принять, отклонить или отметить
     * выполненным. Возвращает `Unit`, хотя сервер отвечает заказом: ответ
     * **без `id`** был бы неотличим от отказа, а показывать ошибку после
     * удачной смены статуса нельзя (то же правило, что у [saveMyService]).
     * Источник правды один — перечитанный список входящих заказов.
     */
    suspend fun updateOrderStatus(orderId: String, status: FreelancerOrderStatus): ApiResult<Unit>

    // --- Кабинет мастера (issue #71) ---

    /**
     * Своя анкета мастера. `null` — **анкеты ещё нет**, и это не ошибка:
     * человек просто не выставлял себя исполнителем (`404` от `freelancers/me`
     * значит именно это).
     */
    suspend fun myProfile(): ApiResult<Freelancer?>

    /**
     * Сохранить анкету — одна ручка и на создание, и на правку.
     *
     * Возвращает `Unit`, хотя сервер отвечает анкетой: ответ **без `id`** был
     * бы неотличим от отказа (по нему потом грузятся услуги), а показывать
     * ошибку после удачного сохранения нельзя. Поэтому источник правды один —
     * перечитанная [myProfile], как и у [toggleAvailability].
     */
    suspend fun saveMyProfile(form: FreelancerProfileForm): ApiResult<Unit>

    /**
     * Свои услуги. Та же ручка, что у клиента, но **без отсева выключенных**:
     * это услуги самого мастера, и спрятать их значило бы соврать про состав.
     */
    suspend fun myServices(freelancerId: String): ApiResult<List<BarberService>>

    /**
     * Выставить услугу или изменить выставленную — решает
     * [FreelancerServiceForm.id]. Возвращает `Unit` по той же причине, что и
     * [saveMyProfile]: список перечитывается у сервера.
     */
    suspend fun saveMyService(form: FreelancerServiceForm): ApiResult<Unit>

    /** Снять услугу. */
    suspend fun deleteMyService(serviceId: String): ApiResult<Unit>

    /**
     * Переключить «принимаю заказы». Новое значение не задаётся, а
     * инвертируется сервером, поэтому и читать его нужно у него же
     * ([myProfile]).
     */
    suspend fun toggleAvailability(): ApiResult<Unit>

    companion object {
        /** Код отказа, когда заказывать нечего ещё до запроса. */
        const val INVALID_REQUEST_CODE = "FREELANCER_ORDER_INVALID"

        /** Код отказа, когда сохранять нечего ещё до запроса (issue #71). */
        const val INVALID_FORM_CODE = "FREELANCER_FORM_INVALID"

        /** Код отказа, когда менять статус нечего ещё до запроса (issue #190). */
        const val INVALID_ORDER_ID_CODE = "FREELANCER_ORDER_ID_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultFreelancerRepository @Inject constructor(
    private val api: FreelancerApi,
    private val phoneValidator: PhoneNumberValidator,
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
        myServices(freelancerId).map { services -> services.filter { it.isActive } }

    override suspend fun myServices(freelancerId: String): ApiResult<List<BarberService>> =
        apiCall { api.services(freelancerId).payload() }
            .map { services -> services.mapNotNull(FreelancerServiceDto::toDomain) }

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

    override suspend fun incomingOrders(page: Int, size: Int): ApiResult<FreelancerOrderPage> =
        apiCall { api.incomingOrders(page = page.coerceAtLeast(0), size = size).payload() }
            .map(FreelancerOrderPageDto::toDomain)

    /**
     * Незнакомый `orderId` в сеть не уходит: сервер ответил бы тем же отказом,
     * но платой были бы запрос и молчание экрана на время его выполнения.
     *
     * Ответ **не разбирается как заказ**: без `id` он был бы неотличим от
     * отказа, хотя статус на сервере уже сменился (тот же риск, что у
     * [saveMyService], если парсить ответ строго). Источник правды —
     * перечитанный [incomingOrders], который вызывает сама ViewModel.
     */
    override suspend fun updateOrderStatus(
        orderId: String,
        status: FreelancerOrderStatus,
    ): ApiResult<Unit> {
        if (orderId.isBlank()) {
            return ApiResult.Failure(ApiError.Business(FreelancerRepository.INVALID_ORDER_ID_CODE))
        }
        return apiCall {
            api.updateOrderStatus(
                orderId = orderId,
                body = UpdateFreelancerOrderStatusRequest(status.apiValue),
            ).ensureSuccess()
        }
    }

    /**
     * Своя анкета. «Анкеты ещё нет» — это ответ, а не отказ: экран тогда
     * показывает пустую форму, а не сообщение об ошибке.
     *
     * Каким именно ответом бэкенд это говорит, проверить было нечем (`401`
     * приходит раньше), поэтому «нет» считаются оба правдоподобных варианта:
     * `404` (так отвечает `GET freelancers/{id}` на неизвестного мастера) и
     * успешный конверт с пустой `data`. Отказ **с кодом** остаётся отказом —
     * молча превращать его в «заполните анкету» нельзя.
     *
     * Ответ **без `id`** — тоже «нет»: по этому id грузятся услуги, и анкета
     * без него для кабинета бесполезна.
     */
    override suspend fun myProfile(): ApiResult<Freelancer?> {
        val result = apiCall { api.myProfile().payload() }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Failure -> {
                val error = result.error
                val absent = error == ApiError.NotFound || error == ApiError.Business(null)
                if (absent) ApiResult.Success(null) else result
            }
        }
    }

    /**
     * Незаполненная анкета в сеть не уходит: `400` от сервера сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения (то же правило, что в анкете продавца, issue #84).
     */
    override suspend fun saveMyProfile(form: FreelancerProfileForm): ApiResult<Unit> {
        val trimmed = form.trimmed()
        val errors = FreelancerProfileFormValidator.validate(trimmed, phoneValidator::isValid)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(FreelancerRepository.INVALID_FORM_CODE))
        }

        return apiCall {
            api.saveMyProfile(
                FreelancerCreateRequest(
                    name = trimmed.name,
                    profession = trimmed.profession,
                    bio = trimmed.bio.takeIf(String::isNotEmpty),
                    city = trimmed.city.takeIf(String::isNotEmpty),
                    // Номер уходит в E.164, как в анкете продавца: домен
                    // хранит национальные цифры, а бэкенду нужен полный номер.
                    phone = trimmed.phoneDigits
                        .takeIf(String::isNotEmpty)
                        ?.let(phoneValidator::toE164),
                    hourlyRate = trimmed.hourlyRate,
                    experienceYears = trimmed.experienceYears,
                ),
            ).ensureSuccess()
        }
    }

    override suspend fun saveMyService(form: FreelancerServiceForm): ApiResult<Unit> {
        val trimmed = form.trimmed()
        val price = trimmed.price
        if (price == null || FreelancerServiceFormValidator.validate(trimmed).isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(FreelancerRepository.INVALID_FORM_CODE))
        }

        val body = FreelancerServiceRequest(
            title = trimmed.title,
            priceAmount = price,
            description = trimmed.description.takeIf(String::isNotEmpty),
            durationMinutes = trimmed.duration,
        )
        val serviceId = trimmed.id
        return apiCall {
            if (serviceId == null) {
                api.createMyService(body).ensureSuccess()
            } else {
                api.updateMyService(serviceId = serviceId, body = body).ensureSuccess()
            }
        }
    }

    override suspend fun deleteMyService(serviceId: String): ApiResult<Unit> {
        if (serviceId.isBlank()) {
            return ApiResult.Failure(ApiError.Business(FreelancerRepository.INVALID_FORM_CODE))
        }
        return apiCall { api.deleteMyService(serviceId).ensureSuccess() }
    }

    override suspend fun toggleAvailability(): ApiResult<Unit> =
        apiCall { api.toggleAvailability().ensureSuccess() }
}
