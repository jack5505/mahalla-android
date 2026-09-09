package uz.mahalla.feature.business.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.MyPlacePage
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.testutil.FakeProviderRepository

/**
 * Репозиторий бизнес-панели (эпик #16).
 *
 * Проверяются две вещи, которые нигде больше не видны: как ищется доступ по
 * страницам `places/my` и что уходит в тело смены статуса заказа — ключ
 * `status` выведен из соседних схем бэкенда, и опечатка в нём молча
 * превратила бы каждую смену статуса в 400.
 */
class BusinessRepositoryTest {

    @Test
    fun `the access is found on the first page`() = runTest {
        val provider = FakeProviderRepository()
        provider.defaultPage = ApiResult.Success(MyPlacePage(items = listOf(place("p-1"))))

        val result = repository(provider).access("p-1")

        assertEquals("p-1", (result as ApiResult.Success).data.placeId)
        assertEquals(listOf(0), provider.requestedPages)
    }

    /** У владельца сети второе кафе лежит на второй странице. */
    @Test
    fun `the access is found on a later page`() = runTest {
        val provider = FakeProviderRepository()
        provider.pages[0] = ApiResult.Success(
            MyPlacePage(items = listOf(place("p-1")), hasMore = true),
        )
        provider.pages[1] = ApiResult.Success(MyPlacePage(items = listOf(place("p-2"))))

        val result = repository(provider).access("p-2")

        assertEquals("p-2", (result as ApiResult.Success).data.placeId)
        assertEquals(listOf(0, 1), provider.requestedPages)
    }

    @Test
    fun `a place that is not mine gives a business refusal`() = runTest {
        val provider = FakeProviderRepository()
        provider.defaultPage = ApiResult.Success(MyPlacePage(items = listOf(place("p-1"))))

        val result = repository(provider).access("p-999")

        assertEquals(
            ApiError.Business(BusinessRepository.NO_ACCESS_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    /**
     * «Проверьте интернет» и «это не ваше заведение» — разные вещи, и вторая
     * фраза на первой причине заставила бы искать чужую ссылку там, где просто
     * пропала связь.
     */
    @Test
    fun `a network failure is not turned into no-access`() = runTest {
        val provider = FakeProviderRepository()
        provider.defaultPage = ApiResult.Failure(ApiError.NoConnection)

        val result = repository(provider).access("p-1")

        assertEquals(ApiError.NoConnection, (result as ApiResult.Failure).error)
    }

    @Test
    fun `an empty id does not go to the network at all`() = runTest {
        val provider = FakeProviderRepository()

        val result = repository(provider).access("   ")

        assertTrue(provider.requestedPages.isEmpty())
        assertEquals(
            ApiError.Business(BusinessRepository.NO_ACCESS_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `the status update sends the status under the key named status`() = runTest {
        val api = RecordingBusinessApi()
        api.orderResponse = BusinessOrderDto(id = "o-1", status = "ACCEPTED")

        val result = repository(api = api)
            .updateOrderStatus("p-1", "o-1", OrderStatus.Confirmed)

        assertEquals(mapOf("status" to "ACCEPTED"), api.statusBody)
        assertEquals(OrderStatus.Confirmed, (result as ApiResult.Success).data.status)
    }

    /** `"UNKNOWN"` — значение приложения, а не бэкенда: в сеть оно не уходит. */
    @Test
    fun `an unknown status is not sent to the server`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).updateOrderStatus("p-1", "o-1", OrderStatus.Unknown)

        assertNull(api.statusBody)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    /**
     * Ответ без `id` — не отказ: действие сервер выполнил, а идентификатор мы
     * и так знаем.
     */
    @Test
    fun `an order response without an id still counts as a success`() = runTest {
        val api = RecordingBusinessApi()
        api.orderResponse = BusinessOrderDto(status = "PREPARING")

        val result = repository(api = api).updateOrderStatus("p-1", "o-7", OrderStatus.Preparing)

        val order = (result as ApiResult.Success).data
        assertEquals("o-7", order.id)
        assertEquals(OrderStatus.Preparing, order.status)
    }

    @Test
    fun `accepting a ticket sends an empty body - the key names are unknown`() = runTest {
        val api = RecordingBusinessApi()
        api.queueEntryResponse = QueueEntryDto(id = "t-1", status = "WAITING")

        val result = repository(api = api).act("p-1", "t-1", QueueAction.Accept)

        assertEquals(emptyMap<String, Int>(), api.acceptBody)
        assertEquals(WalkInStatus.Waiting, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `the stop list toggle flips the flag known to the app`() = runTest {
        val api = RecordingBusinessApi()

        assertEquals(
            false,
            (repository(api = api).toggleStopList("i-1", current = true) as ApiResult.Success).data,
        )
        assertEquals(
            true,
            (
                repository(api = api).toggleStopList("i-1", current = false)
                    as ApiResult.Success
                ).data,
        )
    }

    @Test
    fun `a dashboard map is preserved key by key`() = runTest {
        val api = RecordingBusinessApi()
        api.dashboardResponse = mapOf("totalOrders" to 12L, "revenue" to 500_000L, "bad" to null)

        val dashboard = (repository(api = api).dashboard("p-1") as ApiResult.Success).data

        assertEquals(listOf("totalOrders", "revenue"), dashboard.metrics.map { it.key })
        assertEquals(listOf(12L, 500_000L), dashboard.metrics.map { it.value })
    }

    @Test
    fun `an order is mapped with its lines and totals`() = runTest {
        val api = RecordingBusinessApi()
        api.ordersResponse = BusinessOrderPageDto(
            content = listOf(
                BusinessOrderDto(
                    id = "o-1",
                    orderNumber = "F-42",
                    status = "READY",
                    fulfillment = "DELIVERY",
                    paymentMethod = "CASH",
                    totalAmount = 84_000,
                    items = listOf(
                        BusinessOrderItemDto(
                            itemId = "i-1",
                            itemName = "Osh",
                            quantity = 2,
                            unitPrice = 32_000,
                            // Сервер промолчал об итоге строки — считаем сами.
                            totalPrice = null,
                        ),
                    ),
                ),
            ),
            last = true,
        )

        val page = (repository(api = api).orders("p-1") as ApiResult.Success).data

        val order = page.items.single()
        assertEquals(OrderStatus.ReadyForPickup, order.status)
        assertEquals(DeliveryMethod.Delivery, order.method)
        assertEquals(64_000L, order.lines.single().totalPriceSum)
        assertEquals(false, page.hasMore)
    }

    /** Позиция без `id` в списке — дубликат ключа в `LazyColumn`. */
    @Test
    fun `a menu item without an id is dropped`() = runTest {
        val api = RecordingBusinessApi()
        api.menuResponse = listOf(
            MenuSectionDto(
                id = "s-1",
                name = "Issiq",
                items = listOf(
                    MenuItemDto(id = "i-1", name = "Osh", price = 32_000),
                    MenuItemDto(id = null, name = "Lost", price = 1),
                ),
            ),
            // Раздел без id: `CreateItemRequest` требует `menuId`, добавить в
            // такой раздел всё равно нечего.
            MenuSectionDto(id = null, items = listOf(MenuItemDto(id = "i-2"))),
        )

        val menu = (repository(api = api).menu("p-1") as ApiResult.Success).data

        assertEquals(1, menu.sections.size)
        assertEquals(listOf("i-1"), menu.sections.single().items.map { it.id })
    }

    /** Молчание сервера — «в продаже»: увести всё меню в стоп-лист хуже. */
    @Test
    fun `a menu item without the availability flag stays on sale`() = runTest {
        val api = RecordingBusinessApi()
        api.menuResponse = listOf(
            MenuSectionDto(id = "s-1", items = listOf(MenuItemDto(id = "i-1", name = "Osh"))),
        )

        val menu = (repository(api = api).menu("p-1") as ApiResult.Success).data

        assertTrue(menu.item("i-1")!!.isAvailable)
    }

    /**
     * Предел листания есть затем, чтобы один открытый deep link на чужое
     * заведение не означал обход всего списка.
     */
    @Test
    fun `the search for the access stops at the page limit`() = runTest {
        val provider = FakeProviderRepository()
        provider.defaultPage = ApiResult.Success(
            MyPlacePage(items = listOf(place("p-1")), hasMore = true),
        )

        val result = repository(provider).access("p-999")

        assertEquals(
            BusinessRepository.ACCESS_PAGE_LIMIT,
            provider.requestedPages.size,
        )
        assertEquals(
            ApiError.Business(BusinessRepository.NO_ACCESS_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `an invalid form is refused before the request`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(sectionId = "s-1", name = "", priceText = ""),
        )

        assertNull(api.createdItem)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    /** `CreateItemRequest` требует `menuId` — форма без раздела в сеть не идёт. */
    @Test
    fun `a form without a section is refused before the request`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(sectionId = "", name = "Osh", priceText = "32000"),
        )

        assertNull(api.createdItem)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    /**
     * Пустые необязательные поля уходят **отсутствующими**: `explicitNulls =
     * false` выбрасывает их из тела, и бэкенд получает ровно то, что человек
     * заполнил. `isHalal = false` — то же самое: отдельного «не халяль» у
     * позиции нет.
     */
    @Test
    fun `an optional field left empty is not sent at all`() = runTest {
        val api = RecordingBusinessApi()

        repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(sectionId = "s-1", name = "Osh", priceText = "32000"),
        )

        val sent = api.createdItem!!
        assertEquals("s-1", sent.menuId)
        assertEquals("Osh", sent.name)
        assertEquals(32_000L, sent.price)
        assertNull(sent.description)
        assertNull(sent.prepMinutes)
        assertNull(sent.isHalal)
    }

    @Test
    fun `a filled form sends every field`() = runTest {
        val api = RecordingBusinessApi()

        repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(
                sectionId = "s-1",
                name = "  Osh  ",
                priceText = "32000",
                description = "  Palov  ",
                prepMinutesText = "25",
                isHalal = true,
            ),
        )

        val sent = api.createdItem!!
        assertEquals("Osh", sent.name)
        assertEquals("Palov", sent.description)
        assertEquals(25, sent.prepMinutes)
        assertEquals(true, sent.isHalal)
    }

    /**
     * Ответ `ItemResponse` не говорит, в какой раздел позиция легла, если
     * бэкенд решил иначе, — поэтому меню перечитывается целиком.
     */
    @Test
    fun `the menu is re-read after a successful create`() = runTest {
        val api = RecordingBusinessApi()
        api.menuResponse = listOf(
            MenuSectionDto(
                id = "s-1",
                items = listOf(MenuItemDto(id = "i-1", name = "Osh", price = 32_000)),
            ),
        )

        val result = repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(sectionId = "s-1", name = "Osh", priceText = "32000"),
        )

        assertEquals(listOf("i-1"), (result as ApiResult.Success).data.sections.single().items.map { it.id })
    }

    @Test
    fun `a refused create does not re-read the menu`() = runTest {
        val api = FailingCreateApi()

        val result = repository(api = api).createItem(
            "p-1",
            NewMenuItemForm(sectionId = "s-1", name = "Osh", priceText = "32000"),
        )

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, api.menuCalls)
    }

    @Test
    fun `declining a ticket sends an empty body - the key name is unknown`() = runTest {
        val api = RecordingBusinessApi()
        api.queueEntryResponse = QueueEntryDto(id = "t-1", status = "DECLINED")

        val result = repository(api = api).act("p-1", "t-1", QueueAction.Decline)

        assertEquals(emptyMap<String, String>(), api.declineBody)
        assertEquals(WalkInStatus.Declined, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `the queue drops the entries without an id`() = runTest {
        val api = RecordingBusinessApi()
        api.queueResponse = listOf(
            QueueEntryDto(id = "t-1", userName = "Alisher", status = "WAITING", queuePosition = 1),
            QueueEntryDto(id = null, userName = "Lost", status = "WAITING"),
        )

        val entries = (repository(api = api).queue("p-1") as ApiResult.Success).data

        assertEquals(listOf("t-1"), entries.map { it.id })
        assertEquals(1, entries.single().queuePosition)
    }

    private fun repository(
        provider: FakeProviderRepository = FakeProviderRepository(),
        api: BusinessApi = RecordingBusinessApi(),
    ) = DefaultBusinessRepository(api = api, providerRepository = provider)

    private fun place(id: String) = MyPlace(
        id = id,
        name = "Place $id",
        category = PlaceCategory.Food,
        status = PlaceModerationStatus.Active,
        staffRole = PlaceStaffRole.Owner,
    )
}

/** Создание отказывает: меню после отказа перечитываться не должно. */
private class FailingCreateApi : BusinessApi by RecordingBusinessApi() {

    var menuCalls = 0

    override suspend fun menu(placeId: String): ApiResponse<List<MenuSectionDto>> {
        menuCalls++
        return ApiResponse(data = emptyList())
    }

    override suspend fun createItem(
        placeId: String,
        body: CreateMenuItemRequest,
    ): ApiResponse<MenuItemDto> = ApiResponse(success = false, data = null)
}

/**
 * API панели в памяти: MockWebServer тут не нужен — проверяются тела запросов
 * и разбор ответов, а не сам HTTP (он общий и покрыт в `data/network`).
 */
private class RecordingBusinessApi : BusinessApi {

    var dashboardResponse: Map<String, Long?> = emptyMap()
    var queueResponse: List<QueueEntryDto> = emptyList()
    var queueEntryResponse: QueueEntryDto = QueueEntryDto(id = "t-1", status = "WAITING")
    var ordersResponse: BusinessOrderPageDto = BusinessOrderPageDto()
    var orderResponse: BusinessOrderDto = BusinessOrderDto(id = "o-1", status = "NEW")
    var menuResponse: List<MenuSectionDto> = emptyList()
    var itemResponse: MenuItemDto = MenuItemDto(id = "i-1")

    var acceptBody: Map<String, Int>? = null
    var declineBody: Map<String, String>? = null
    var statusBody: Map<String, String>? = null
    var createdItem: CreateMenuItemRequest? = null

    override suspend fun dashboard(placeId: String) = ApiResponse(data = dashboardResponse)

    override suspend fun queue(placeId: String) = ApiResponse(data = queueResponse)

    override suspend fun accept(
        ticketId: String,
        placeId: String,
        body: Map<String, Int>,
    ): ApiResponse<QueueEntryDto> {
        acceptBody = body
        return ApiResponse(data = queueEntryResponse)
    }

    override suspend fun decline(
        ticketId: String,
        placeId: String,
        body: Map<String, String>,
    ): ApiResponse<QueueEntryDto> {
        declineBody = body
        return ApiResponse(data = queueEntryResponse)
    }

    override suspend fun start(ticketId: String, placeId: String) =
        ApiResponse(data = queueEntryResponse)

    override suspend fun complete(ticketId: String, placeId: String) =
        ApiResponse(data = queueEntryResponse)

    override suspend fun orders(
        placeId: String,
        status: String?,
        page: Int,
        size: Int,
    ) = ApiResponse(data = ordersResponse)

    override suspend fun updateOrderStatus(
        placeId: String,
        orderId: String,
        body: Map<String, String>,
    ): ApiResponse<BusinessOrderDto> {
        statusBody = body
        return ApiResponse(data = orderResponse)
    }

    override suspend fun menu(placeId: String) = ApiResponse(data = menuResponse)

    /** `ApiResponseVoid`: `data` пустая и при успехе. */
    override suspend fun toggleItem(itemId: String) =
        ApiResponse<kotlinx.serialization.json.JsonElement>(success = true)

    override suspend fun createItem(
        placeId: String,
        body: CreateMenuItemRequest,
    ): ApiResponse<MenuItemDto> {
        createdItem = body
        return ApiResponse(data = itemResponse)
    }
}
