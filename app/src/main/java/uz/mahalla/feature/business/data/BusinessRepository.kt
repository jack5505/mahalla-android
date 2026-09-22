package uz.mahalla.feature.business.data

import uz.mahalla.core.format.Money
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.booking.data.AppointmentPageDto
import uz.mahalla.feature.booking.data.toDomain
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.NewMenuItemValidator
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.business.domain.orderVerticalApiValue
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.fashion.data.FashionApi
import uz.mahalla.feature.fashion.data.OrderPageDto
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.hospital.data.HospitalRepository
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.role.data.ProviderRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Бизнес-панель заведения (эпик #16).
 *
 * Кэша нет ни у одного метода, и это не упрощение: панель показывает то, что
 * происходит **прямо сейчас** — очередь у кресла, заказы на кухне. Заказ из
 * Room, отменённый пять минут назад, здесь хуже пустого экрана.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer
 * (`FakeBusinessRepository`).
 */
interface BusinessRepository {

    /**
     * Есть ли у человека доступ к панели этого заведения и в каком объёме.
     *
     * Единственный источник правды — `GET places/my`: бэкенд возвращает только
     * «свои» заведения вместе с ролью. Заведения там нет — панели нет, и
     * дальше ни одна ручка не зовётся.
     */
    suspend fun access(placeId: String): ApiResult<BusinessAccess>

    /** Метрики дня (задача 12.1). */
    suspend fun dashboard(placeId: String): ApiResult<BusinessDashboard>

    /** Живая очередь (задача 12.2). */
    suspend fun queue(placeId: String): ApiResult<List<QueueEntry>>

    /**
     * Действие над талоном (задача 12.2).
     *
     * @return талон после действия — сервер отдаёт его целиком, и это
     * единственный надёжный способ узнать новый статус.
     */
    suspend fun act(
        placeId: String,
        ticketId: String,
        action: QueueAction,
    ): ApiResult<QueueEntry>

    /**
     * «Пауза» заведения — тот же переключатель «открыто сейчас», что и в
     * «моих заведениях». Своей ручки у панели нет, поэтому зовётся
     * [ProviderRepository]: дублировать `PUT places/{id}/availability` вторым
     * запросом с той же лестницей координат значило бы завести второе место,
     * где это правило может разойтись.
     *
     * @return состояние флага после запроса.
     */
    suspend fun togglePause(placeId: String, current: Boolean): ApiResult<Boolean>

    /**
     * Единая лента входящих (задача 12.3, issue #289): `GET places/{placeId}/
     * orders`, одна ручка на все вертикали вместо разрозненных списков
     * (issue #187).
     *
     * @param vertical фильтр по вертикали; `null` — заказы всех вертикалей
     * разом, так же, как и с [status].
     */
    suspend fun orders(
        placeId: String,
        status: String? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
        vertical: PlaceCategory? = null,
    ): ApiResult<BusinessOrderPage>

    /**
     * Сменить статус заказа (задача 12.3): переиспользует те же ручки, что и
     * #187 — `food/places/{id}/orders/{id}/status` /
     * `fashion/stores/{id}/orders/{id}/status`, — единая лента (issue #289)
     * читает заказы, но не меняет их статус общей ручкой (её у бэкенда нет).
     *
     * @param category вертикаль **конкретного заказа** ([BusinessOrder.vertical]
     * из строки списка), а не заведения: в единой ленте это одно и то же для
     * настоящих заведений, но вызывающий обязан передать то, что показал
     * список, а не догадку.
     */
    suspend fun updateOrderStatus(
        placeId: String,
        orderId: String,
        status: OrderStatus,
        category: PlaceCategory = PlaceCategory.Food,
    ): ApiResult<BusinessOrder>

    /**
     * Журнал записей на день (issue #289): барбершоп —
     * `GET appointments/places/{id}`, клиника —
     * `GET hospitals/places/{id}/appointments`.
     *
     * @param doctorId фильтр по врачу — только у [AppointmentVertical.Doctor],
     * барбершоп его игнорирует (в барберском журнале мастеров не разводят).
     */
    suspend fun journal(
        placeId: String,
        vertical: AppointmentVertical,
        date: LocalDate,
        doctorId: String? = null,
        status: AppointmentStatus? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<AppointmentPage>

    /** Сменить статус записи журнала. См. [journal] про [vertical]. */
    suspend fun updateAppointmentStatus(
        placeId: String,
        appointmentId: String,
        vertical: AppointmentVertical,
        status: AppointmentStatus,
    ): ApiResult<Appointment>

    /**
     * Врачи заведения — фильтр журнала клиники (issue #289). Делегирует
     * [HospitalRepository]: своей ручки у панели нет, а вторую реализацию той
     * же `GET hospitals/places/{id}/doctors` заводить незачем.
     */
    suspend fun doctors(placeId: String): ApiResult<List<Doctor>>

    /** Меню со стоп-листом (задача 12.4). */
    suspend fun menu(placeId: String): ApiResult<BusinessMenu>

    /**
     * Перевернуть стоп-лист позиции.
     *
     * @param current известное приложению состояние. Нужно потому, что ручка —
     * переключатель без ответа (`ApiResponseVoid`): нового значения сервер не
     * сообщает вовсе, и «перевернулось» — единственный вывод, который можно
     * сделать из успеха (то же решение, что у `toggleAvailability`, issue #94).
     * @return состояние флага после запроса.
     */
    suspend fun toggleStopList(itemId: String, current: Boolean): ApiResult<Boolean>

    /** Новая позиция меню (задача 12.4). */
    suspend fun createItem(placeId: String, form: NewMenuItemForm): ApiResult<BusinessMenu>

    /** Правка позиции меню (issue #288, задача 12.4 бэкенда — #221). */
    suspend fun updateItem(placeId: String, form: NewMenuItemForm): ApiResult<BusinessMenu>

    /** Удаление позиции меню (issue #288, задача 12.4 бэкенда — #221). */
    suspend fun deleteItem(placeId: String, itemId: String): ApiResult<BusinessMenu>

    companion object {
        /** Код отказа, когда заведения нет среди «моих». */
        const val NO_ACCESS_CODE = BusinessAccess.NO_ACCESS_CODE

        /** Код отказа, когда форма не прошла проверку ещё на клиенте. */
        const val INVALID_FORM_CODE = "BUSINESS_FORM_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20

        /**
         * Сколько страниц `places/my` перелистать в поисках заведения.
         *
         * Ограничение есть потому, что ручка пагинирована, а фильтра по id у
         * неё нет: без предела один открытый deep link на чужое заведение
         * означал бы обход всего списка. Двадцать страниц по двадцать — это
         * четыреста заведений у одного человека; дальше честнее ответить
         * «доступа нет», чем молча листать.
         */
        const val ACCESS_PAGE_LIMIT = 20
    }
}

@Singleton
class DefaultBusinessRepository @Inject constructor(
    private val api: BusinessApi,
    private val fashionApi: FashionApi,
    private val providerRepository: ProviderRepository,
    private val hospitalRepository: HospitalRepository,
) : BusinessRepository {

    /**
     * Доступ ищется перелистыванием `places/my`: фильтра по id у ручки нет.
     *
     * Первая же страница обычно и последняя — заведений у человека единицы, —
     * но останавливаться на ней нельзя: у владельца сети панель второго кафе
     * иначе не открылась бы вовсе.
     *
     * Отказ сети отдаётся как есть, а не превращается в «доступа нет»:
     * «проверьте интернет» и «это не ваше заведение» — разные вещи, и вторая
     * фраза на первой причине заставила бы человека искать чужую ссылку там,
     * где просто пропала связь.
     */
    override suspend fun access(placeId: String): ApiResult<BusinessAccess> {
        val wanted = placeId.trim()
        if (wanted.isEmpty()) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.NO_ACCESS_CODE))
        }

        var page = 0
        while (page < BusinessRepository.ACCESS_PAGE_LIMIT) {
            when (val result = providerRepository.myPlaces(page = page)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val place = result.data.items.firstOrNull { it.id == wanted }
                    if (place != null) return ApiResult.Success(BusinessAccess.from(place))
                    if (!result.data.hasMore) break
                    page++
                }
            }
        }
        return ApiResult.Failure(ApiError.Business(BusinessRepository.NO_ACCESS_CODE))
    }

    override suspend fun dashboard(placeId: String): ApiResult<BusinessDashboard> =
        apiCall { api.dashboard(placeId).payload() }.map(BusinessDashboard::from)

    override suspend fun queue(placeId: String): ApiResult<List<QueueEntry>> =
        apiCall { api.queue(placeId).payload() }
            .map { entries -> entries.mapNotNull(QueueEntryDto::toDomain) }

    /**
     * Тела `accept` и `decline` уходят **пустыми объектами**: имён ключей
     * схема не знает (см. [BusinessApi]), а угаданный ключ бэкенд молча
     * выбросит. Пустой объект — это «принять как есть» и «отказать без
     * причины», то есть ровно то, что предлагает экран.
     */
    override suspend fun act(
        placeId: String,
        ticketId: String,
        action: QueueAction,
    ): ApiResult<QueueEntry> = apiCall {
        val dto = when (action) {
            QueueAction.Accept -> api.accept(ticketId, placeId, emptyMap())
            QueueAction.Decline -> api.decline(ticketId, placeId, emptyMap())
            QueueAction.Start -> api.start(ticketId, placeId)
            QueueAction.Complete -> api.complete(ticketId, placeId)
        }.payload()

        // Талон без `id` в ответе — не отказ: действие сервер выполнил, а
        // идентификатор мы и так знаем. Без этой подстановки удачный вызов
        // показался бы экрану ошибкой разбора.
        dto.toDomain() ?: dto.copy(id = ticketId).toDomain()
            ?: error("walkin response without status for $ticketId")
    }

    override suspend fun togglePause(placeId: String, current: Boolean): ApiResult<Boolean> =
        providerRepository.toggleAvailability(placeId = placeId, current = current)

    override suspend fun orders(
        placeId: String,
        status: String?,
        page: Int,
        size: Int,
        vertical: PlaceCategory?,
    ): ApiResult<BusinessOrderPage> = apiCall {
        api.placeOrders(
            placeId = placeId,
            vertical = vertical?.orderVerticalApiValue(),
            status = status?.takeIf(String::isNotBlank),
            page = page.coerceAtLeast(0),
            size = size,
        ).payload()
    }.map(OrderPageDto::toDomain)

    /**
     * Ключ тела — `status`: имя выведено из настоящих схем той же операции у
     * соседних вертикалей (`UpdateOrderStatusRequest`, `ModerateRequest`), см.
     * [BusinessApi.updateOrderStatus].
     *
     * [OrderStatus.Unknown] в сеть не уходит: `"UNKNOWN"` — значение
     * приложения, а не бэкенда, и отправить его значило бы получить 400 там,
     * где вопрос в самом клиенте.
     */
    override suspend fun updateOrderStatus(
        placeId: String,
        orderId: String,
        status: OrderStatus,
        category: PlaceCategory,
    ): ApiResult<BusinessOrder> {
        if (status == OrderStatus.Unknown) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.INVALID_FORM_CODE))
        }
        return when (category) {
            PlaceCategory.Fashion -> apiCall {
                val dto = fashionApi.updateStoreOrderStatus(
                    storeId = placeId,
                    orderId = orderId,
                    body = mapOf(STATUS_KEY to status.apiValue),
                ).payload()
                // Как и у талона: ответ без `id` — это удачная смена статуса,
                // а не потерянный заказ.
                dto.toDomain(category) ?: dto.copy(id = orderId).toDomain(category)
                    ?: error("fashion order response without status for $orderId")
            }

            else -> apiCall {
                val dto = api.updateOrderStatus(
                    placeId = placeId,
                    orderId = orderId,
                    body = mapOf(STATUS_KEY to status.apiValue),
                ).payload()
                dto.toDomain(category) ?: dto.copy(id = orderId).toDomain(category)
                    ?: error("order response without status for $orderId")
            }
        }
    }

    /**
     * Клиника дотягивает имя врача тем же [HospitalRepository.withDoctorNames],
     * что и «мои записи»: `HospitalAppointmentResponse` называет только
     * `doctorId` (issue #219), а без имени журнал был бы списком одинаковых
     * заглушек. Барбершоп имя услуги уже получает в ответе — дотягивать
     * нечего.
     */
    override suspend fun journal(
        placeId: String,
        vertical: AppointmentVertical,
        date: LocalDate,
        doctorId: String?,
        status: AppointmentStatus?,
        page: Int,
        size: Int,
    ): ApiResult<AppointmentPage> = when (vertical) {
        AppointmentVertical.Barber -> apiCall {
            api.barberJournal(
                placeId = placeId,
                date = date.toString(),
                status = status?.apiValueOrNull(),
                page = page.coerceAtLeast(0),
                size = size,
            ).payload()
        }.map(AppointmentPageDto::toDomain)

        AppointmentVertical.Doctor -> apiCall {
            val dto = api.clinicJournal(
                placeId = placeId,
                doctorId = doctorId?.takeIf(String::isNotBlank),
                date = date.toString(),
                status = status?.apiValueOrNull(),
                page = page.coerceAtLeast(0),
                size = size,
            ).payload()
            dto.copy(content = hospitalRepository.withDoctorNames(dto.content))
        }.map(AppointmentPageDto::toDomain)
    }

    /**
     * Ключ тела — `status`, тот же вывод, что у [updateOrderStatus]: своей
     * схемы у ручки нет, а операция совпадает с соседними.
     *
     * Ответ без `id` — не отказ, тем же приёмом, что и у заказа: действие
     * сервер выполнил, а идентификатор мы и так знаем ([AppointmentDto.toDomain]
     * с запрошенным `id` из `BookingMappers.kt`).
     */
    override suspend fun updateAppointmentStatus(
        placeId: String,
        appointmentId: String,
        vertical: AppointmentVertical,
        status: AppointmentStatus,
    ): ApiResult<Appointment> {
        if (status == AppointmentStatus.Unknown) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.INVALID_FORM_CODE))
        }
        return when (vertical) {
            AppointmentVertical.Barber -> apiCall {
                api.updateAppointmentStatus(
                    appointmentId = appointmentId,
                    body = mapOf(STATUS_KEY to status.apiValue),
                ).payload().toDomain(appointmentId)
            }

            AppointmentVertical.Doctor -> apiCall {
                api.updateClinicAppointmentStatus(
                    placeId = placeId,
                    appointmentId = appointmentId,
                    body = mapOf(STATUS_KEY to status.apiValue),
                ).payload().toDomain(appointmentId)
            }
        }
    }

    override suspend fun doctors(placeId: String): ApiResult<List<Doctor>> =
        hospitalRepository.doctors(placeId)

    override suspend fun menu(placeId: String): ApiResult<BusinessMenu> =
        apiCall { api.menu(placeId).payload() }.map(List<MenuSectionDto>::toDomain)

    /**
     * `ensureSuccess`, а не `payload`: ответ — `ApiResponseVoid`, и `data` там
     * `null` даже при успехе. Новое состояние выводится из известного:
     * переключатель.
     */
    override suspend fun toggleStopList(itemId: String, current: Boolean): ApiResult<Boolean> =
        apiCall {
            api.toggleItem(itemId).ensureSuccess()
            !current
        }

    /**
     * Незаполненная форма в сеть не уходит: 400 от сервера сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения (то же правило, что в анкете продавца, issue #84).
     *
     * После создания меню перечитывается целиком, а не дописывается в список:
     * ответ `ItemResponse` не говорит, в какой раздел позиция легла, если
     * бэкенд решил иначе, — а меню с блюдом не в том разделе хуже лишнего
     * запроса.
     */
    override suspend fun createItem(
        placeId: String,
        form: NewMenuItemForm,
    ): ApiResult<BusinessMenu> {
        val trimmed = form.trimmed()
        val errors = NewMenuItemValidator.validate(trimmed)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.INVALID_FORM_CODE))
        }

        val created = apiCall {
            api.createItem(
                placeId = placeId,
                body = CreateMenuItemRequest(
                    menuId = trimmed.sectionId,
                    name = trimmed.name,
                    // Валидатор уже подтвердил, что цена — число в границах;
                    // форма принимает сумы, бэкенд — тийины (issue #149).
                    price = Money.somToTiyin(
                        trimmed.priceOrNull() ?: NewMenuItemForm.MIN_PRICE_SUM,
                    ),
                    description = trimmed.description.takeIf(String::isNotEmpty),
                    prepMinutes = trimmed.prepMinutesOrNull(),
                    isHalal = trimmed.isHalal.takeIf { it },
                ),
            ).payload()
        }
        return when (created) {
            is ApiResult.Failure -> created
            is ApiResult.Success -> menu(placeId)
        }
    }

    /**
     * Правка позиции меню (issue #288). Тот же приём, что у [createItem]:
     * форма проверяется на клиенте, а после успеха меню перечитывается
     * целиком — ответ `ItemResponse` не говорит, остался ли раздел тем же,
     * если бэкенд решил иначе.
     */
    override suspend fun updateItem(
        placeId: String,
        form: NewMenuItemForm,
    ): ApiResult<BusinessMenu> {
        val itemId = form.itemId ?: return ApiResult.Failure(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
        )
        val trimmed = form.trimmed()
        val errors = NewMenuItemValidator.validate(trimmed)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.INVALID_FORM_CODE))
        }

        val updated = apiCall {
            api.updateItem(
                itemId = itemId,
                body = UpdateMenuItemRequest(
                    menuId = trimmed.sectionId,
                    name = trimmed.name,
                    price = Money.somToTiyin(
                        trimmed.priceOrNull() ?: NewMenuItemForm.MIN_PRICE_SUM,
                    ),
                    description = trimmed.description.takeIf(String::isNotEmpty),
                    prepMinutes = trimmed.prepMinutesOrNull(),
                    isHalal = trimmed.isHalal.takeIf { it },
                ),
            ).payload()
        }
        return when (updated) {
            is ApiResult.Failure -> updated
            is ApiResult.Success -> menu(placeId)
        }
    }

    /**
     * Удаление позиции меню (issue #288). `ensureSuccess`, а не `payload`: как
     * и у [toggleStopList], ответ — `ApiResponseVoid`. Меню перечитывается у
     * сервера, а не вычёркивается на месте: удаляет ли бэкенд строку или
     * ставит стоп-лист, контракт не говорит (тот же приём, что у
     * `deleteMyService`, issue #71).
     */
    override suspend fun deleteItem(placeId: String, itemId: String): ApiResult<BusinessMenu> {
        val deleted = apiCall { api.deleteItem(itemId).ensureSuccess() }
        return when (deleted) {
            is ApiResult.Failure -> deleted
            is ApiResult.Success -> menu(placeId)
        }
    }

    private companion object {
        const val STATUS_KEY = "status"
    }
}

/** `Unknown.apiValue` — пустая строка приложения, не значение бэкенда: в query её отправлять незачем. */
private fun AppointmentStatus.apiValueOrNull(): String? = apiValue.takeIf(String::isNotEmpty)
