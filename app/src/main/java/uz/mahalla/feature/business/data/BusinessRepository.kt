package uz.mahalla.feature.business.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.NewMenuItemValidator
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.role.data.ProviderRepository
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

    /** Входящие заказы (задача 12.3). */
    suspend fun orders(
        placeId: String,
        status: String? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<BusinessOrderPage>

    /** Сменить статус заказа (задача 12.3). */
    suspend fun updateOrderStatus(
        placeId: String,
        orderId: String,
        status: OrderStatus,
    ): ApiResult<BusinessOrder>

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
    private val providerRepository: ProviderRepository,
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
    ): ApiResult<BusinessOrderPage> = apiCall {
        api.orders(
            placeId = placeId,
            status = status?.takeIf(String::isNotBlank),
            page = page.coerceAtLeast(0),
            size = size,
        ).payload()
    }.map(BusinessOrderPageDto::toDomain)

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
    ): ApiResult<BusinessOrder> {
        if (status == OrderStatus.Unknown) {
            return ApiResult.Failure(ApiError.Business(BusinessRepository.INVALID_FORM_CODE))
        }
        return apiCall {
            val dto = api.updateOrderStatus(
                placeId = placeId,
                orderId = orderId,
                body = mapOf(STATUS_KEY to status.apiValue),
            ).payload()
            // Как и у талона: ответ без `id` — это удачная смена статуса, а не
            // потерянный заказ.
            dto.toDomain() ?: dto.copy(id = orderId).toDomain()
                ?: error("order response without status for $orderId")
        }
    }

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
                    // Валидатор уже подтвердил, что цена — число в границах.
                    price = trimmed.priceOrNull() ?: NewMenuItemForm.MIN_PRICE_SUM,
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

    private companion object {
        const val STATUS_KEY = "status"
    }
}
