package uz.mahalla.feature.business.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.booking.data.AppointmentDto
import uz.mahalla.feature.booking.data.AppointmentPageDto
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.fashion.data.AddToCartRequestDto
import uz.mahalla.feature.fashion.data.CartItemDto
import uz.mahalla.feature.fashion.data.CatalogDto
import uz.mahalla.feature.fashion.data.FashionApi
import uz.mahalla.feature.fashion.data.FashionCategoryDto
import uz.mahalla.feature.fashion.data.FashionPlaceOrderRequestDto
import uz.mahalla.feature.fashion.data.FashionStoreOrderDto
import uz.mahalla.feature.fashion.data.OrderPageDto
import uz.mahalla.feature.fashion.data.ProductDetailDto
import uz.mahalla.feature.food.data.CreatedOrderDto
import uz.mahalla.feature.food.data.OrderItemViewDto
import uz.mahalla.feature.food.data.OrderViewDto
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.MyPlacePage
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.testutil.FakeHospitalRepository
import uz.mahalla.testutil.FakeProviderRepository
import java.time.LocalDate

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
        // "revenue" — деньги (тийины бэкенда, issue #149): 500_000 → 5_000 сум.
        assertEquals(listOf(12L, 5_000L), dashboard.metrics.map { it.value })
    }

    /** Единая лента (issue #289): `GET places/{placeId}/orders`, схема `OrderView`. */
    @Test
    fun `an order is mapped with its lines, totals and vertical`() = runTest {
        val api = RecordingBusinessApi()
        api.placeOrdersResponse = OrderPageDto(
            content = listOf(
                OrderViewDto(
                    id = "o-1",
                    orderNumber = "F-42",
                    vertical = "CLOTHING",
                    status = "READY",
                    fulfillment = "DELIVERY",
                    paymentMethod = "CASH",
                    totalAmount = 84_000,
                    items = listOf(
                        OrderItemViewDto(
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
        // `CLOTHING` — так вертикаль называется в заказе, а не «FASHION»
        // каталога (см. `PlaceCategory.Fashion` KDoc).
        assertEquals(PlaceCategory.Fashion, order.vertical)
        // unitPrice=32_000 тийин × 2 = 64_000 тийин → 640 сум (issue #149).
        assertEquals(640L, order.lines.single().totalPriceSum)
        assertEquals(false, page.hasMore)
    }

    /** `vertical`/`status` уходят в query единой ленты как есть, `null` — не отправляется вовсе. */
    @Test
    fun `the vertical and status filters reach the unified feed`() = runTest {
        val api = RecordingBusinessApi()

        repository(api = api).orders("p-1", status = "READY", vertical = PlaceCategory.Cinema)

        assertEquals("p-1", api.placeOrdersRequest?.placeId)
        assertEquals("CINEMA", api.placeOrdersRequest?.vertical)
        assertEquals("READY", api.placeOrdersRequest?.status)
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
        // Форма даёт сумы, бэкенд принимает тийины (issue #149): 32000 → 3_200_000.
        assertEquals(3_200_000L, sent.price)
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

    /** Правка без `itemId` — не форма правки, значит спрашивать сервер нечем (issue #288). */
    @Test
    fun `an update without an item id is refused before the request`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).updateItem(
            "p-1",
            NewMenuItemForm(sectionId = "s-1", name = "Osh", priceText = "32000"),
        )

        assertNull(api.updatedItem)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `an invalid update form is refused before the request`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).updateItem(
            "p-1",
            NewMenuItemForm(itemId = "i-1", sectionId = "s-1", name = "", priceText = ""),
        )

        assertNull(api.updatedItem)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `a valid update goes to the item's own path with every field`() = runTest {
        val api = RecordingBusinessApi()

        repository(api = api).updateItem(
            "p-1",
            NewMenuItemForm(
                itemId = "i-1",
                sectionId = "s-1",
                name = "  Osh  ",
                priceText = "45000",
                description = "  Palov  ",
                prepMinutesText = "20",
                isHalal = true,
            ),
        )

        assertEquals("i-1", api.updatedItemId)
        val sent = api.updatedItem!!
        assertEquals("s-1", sent.menuId)
        assertEquals("Osh", sent.name)
        // Форма даёт сумы, бэкенд принимает тийины (issue #149): 45000 → 4_500_000.
        assertEquals(4_500_000L, sent.price)
        assertEquals("Palov", sent.description)
        assertEquals(20, sent.prepMinutes)
        assertEquals(true, sent.isHalal)
    }

    /** Ответ `ItemResponse` не говорит про соседние позиции — меню перечитывается целиком. */
    @Test
    fun `the menu is re-read after a successful update`() = runTest {
        val api = RecordingBusinessApi()
        api.menuResponse = listOf(
            MenuSectionDto(
                id = "s-1",
                items = listOf(MenuItemDto(id = "i-1", name = "Osh", price = 45_000)),
            ),
        )

        val result = repository(api = api).updateItem(
            "p-1",
            NewMenuItemForm(itemId = "i-1", sectionId = "s-1", name = "Osh", priceText = "45000"),
        )

        assertEquals(
            listOf("i-1"),
            (result as ApiResult.Success).data.sections.single().items.map { it.id },
        )
    }

    @Test
    fun `a refused update does not re-read the menu`() = runTest {
        val api = FailingUpdateApi()

        val result = repository(api = api).updateItem(
            "p-1",
            NewMenuItemForm(itemId = "i-1", sectionId = "s-1", name = "Osh", priceText = "45000"),
        )

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, api.menuCalls)
    }

    @Test
    fun `a delete goes to the item's own path and the menu is re-read`() = runTest {
        val api = RecordingBusinessApi()
        api.menuResponse = listOf(MenuSectionDto(id = "s-1", items = emptyList()))

        val result = repository(api = api).deleteItem("p-1", "i-1")

        assertEquals("i-1", api.deletedItemId)
        assertTrue((result as ApiResult.Success).data.sections.single().items.isEmpty())
    }

    @Test
    fun `a refused delete does not re-read the menu`() = runTest {
        val api = FailingDeleteApi()

        val result = repository(api = api).deleteItem("p-1", "i-1")

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

    /** Смена статуса «Одежды» тоже уходит на `fashion/stores/{id}/orders/{orderId}/status`. */
    @Test
    fun `a fashion status change sends the status under the key named status`() = runTest {
        val fashionApi = RecordingFashionApi()
        fashionApi.updateOrderResponse = FashionStoreOrderDto(id = "o-1", status = "ACCEPTED")

        val result = repository(fashionApi = fashionApi).updateOrderStatus(
            placeId = "store-1",
            orderId = "o-1",
            status = OrderStatus.Confirmed,
            category = PlaceCategory.Fashion,
        )

        assertEquals(mapOf("status" to "ACCEPTED"), fashionApi.updateStatusBody)
        assertEquals(OrderStatus.Confirmed, (result as ApiResult.Success).data.status)
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

    /** Барбершоп читает `GET appointments/places/{id}` (issue #289). */
    @Test
    fun `the barber journal asks the generic appointments endpoint`() = runTest {
        val api = RecordingBusinessApi()
        api.barberJournalResponse = AppointmentPageDto(
            content = listOf(AppointmentDto(id = "a-1", status = "PENDING", serviceName = "Soch olish")),
        )

        val page = (
            repository(api = api).journal(
                placeId = "p-1",
                vertical = AppointmentVertical.Barber,
                date = LocalDate.of(2026, 9, 22),
                status = AppointmentStatus.Pending,
            ) as ApiResult.Success
            ).data

        assertEquals(Triple("p-1", "2026-09-22", "PENDING"), api.barberJournalRequest)
        assertEquals("Soch olish", page.items.single().serviceName)
    }

    /** Клиника читает `GET hospitals/places/{id}/appointments`, с фильтром по врачу (issue #289). */
    @Test
    fun `the clinic journal asks the hospital endpoint with the doctor filter`() = runTest {
        val api = RecordingBusinessApi()
        val hospitalRepository = FakeHospitalRepository()

        repository(api = api, hospitalRepository = hospitalRepository).journal(
            placeId = "p-1",
            vertical = AppointmentVertical.Doctor,
            date = LocalDate.of(2026, 9, 22),
            doctorId = "d-1",
            status = AppointmentStatus.Confirmed,
        )

        assertEquals(listOf("p-1", "d-1", "2026-09-22", "CONFIRMED"), api.clinicJournalRequest)
    }

    /**
     * `HospitalAppointmentResponse` не называет врача — только `doctorId`
     * (issue #219); клиника дотягивает имя тем же [uz.mahalla.feature.hospital.data.HospitalRepository.withDoctorNames],
     * что и «мои записи» (issue #289).
     */
    @Test
    fun `the clinic journal fills in doctor names`() = runTest {
        val api = RecordingBusinessApi()
        api.clinicJournalResponse = AppointmentPageDto(
            content = listOf(AppointmentDto(id = "a-1", doctorId = "d-1", status = "PENDING")),
        )
        val hospitalRepository = FakeHospitalRepository()
        hospitalRepository.withDoctorNamesResult = { items ->
            items.map { it.copy(serviceName = "Dr. Karimova") }
        }

        val page = (
            repository(api = api, hospitalRepository = hospitalRepository).journal(
                placeId = "p-1",
                vertical = AppointmentVertical.Doctor,
                date = LocalDate.of(2026, 9, 22),
            ) as ApiResult.Success
            ).data

        assertEquals("Dr. Karimova", page.items.single().serviceName)
    }

    @Test
    fun `a barber appointment status change sends the status under the key named status`() = runTest {
        val api = RecordingBusinessApi()
        api.appointmentResponse = AppointmentDto(id = "a-1", status = "CONFIRMED")

        val result = repository(api = api).updateAppointmentStatus(
            placeId = "p-1",
            appointmentId = "a-1",
            vertical = AppointmentVertical.Barber,
            status = AppointmentStatus.Confirmed,
        )

        assertEquals(mapOf("status" to "CONFIRMED"), api.appointmentStatusBody)
        assertEquals(AppointmentStatus.Confirmed, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `a clinic appointment status change goes to the place-scoped path`() = runTest {
        val api = RecordingBusinessApi()
        api.appointmentResponse = AppointmentDto(id = "a-1", status = "CANCELLED")

        val result = repository(api = api).updateAppointmentStatus(
            placeId = "p-1",
            appointmentId = "a-1",
            vertical = AppointmentVertical.Doctor,
            status = AppointmentStatus.Cancelled,
        )

        assertEquals(mapOf("status" to "CANCELLED"), api.clinicAppointmentStatusBody)
        assertEquals(AppointmentStatus.Cancelled, (result as ApiResult.Success).data.status)
    }

    /** `"UNKNOWN"` — значение приложения, а не бэкенда: в сеть оно не уходит. */
    @Test
    fun `an unknown appointment status is not sent to the server`() = runTest {
        val api = RecordingBusinessApi()

        val result = repository(api = api).updateAppointmentStatus(
            placeId = "p-1",
            appointmentId = "a-1",
            vertical = AppointmentVertical.Barber,
            status = AppointmentStatus.Unknown,
        )

        assertNull(api.appointmentStatusBody)
        assertEquals(
            ApiError.Business(BusinessRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    /** Доктора клиники — делегат `HospitalRepository`, своей ручки у панели нет (issue #289). */
    @Test
    fun `the doctor filter delegates to the hospital repository`() = runTest {
        val hospitalRepository = FakeHospitalRepository()
        hospitalRepository.doctorsResult = ApiResult.Success(listOf(Doctor(id = "d-1", name = "Dr. Karimova")))

        val doctors = (
            repository(hospitalRepository = hospitalRepository).doctors("p-1") as ApiResult.Success
            ).data

        assertEquals(listOf("p-1"), hospitalRepository.requestedDoctors)
        assertEquals("Dr. Karimova", doctors.single().name)
    }

    private fun repository(
        provider: FakeProviderRepository = FakeProviderRepository(),
        api: BusinessApi = RecordingBusinessApi(),
        fashionApi: FashionApi = FakeFashionApi(),
        hospitalRepository: FakeHospitalRepository = FakeHospitalRepository(),
    ): BusinessRepository = DefaultBusinessRepository(
        api = api,
        fashionApi = fashionApi,
        providerRepository = provider,
        hospitalRepository = hospitalRepository,
    )

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

/** Правка отказывает: меню после отказа перечитываться не должно (issue #288). */
private class FailingUpdateApi : BusinessApi by RecordingBusinessApi() {

    var menuCalls = 0

    override suspend fun menu(placeId: String): ApiResponse<List<MenuSectionDto>> {
        menuCalls++
        return ApiResponse(data = emptyList())
    }

    override suspend fun updateItem(
        itemId: String,
        body: UpdateMenuItemRequest,
    ): ApiResponse<MenuItemDto> = ApiResponse(success = false, data = null)
}

/** Удаление отказывает: меню после отказа перечитываться не должно (issue #288). */
private class FailingDeleteApi : BusinessApi by RecordingBusinessApi() {

    var menuCalls = 0

    override suspend fun menu(placeId: String): ApiResponse<List<MenuSectionDto>> {
        menuCalls++
        return ApiResponse(data = emptyList())
    }

    override suspend fun deleteItem(
        itemId: String,
    ): ApiResponse<kotlinx.serialization.json.JsonElement> = ApiResponse(success = false)
}

/**
 * API панели в памяти: MockWebServer тут не нужен — проверяются тела запросов
 * и разбор ответов, а не сам HTTP (он общий и покрыт в `data/network`).
 */
private class RecordingBusinessApi : BusinessApi {

    var dashboardResponse: Map<String, Long?> = emptyMap()
    var queueResponse: List<QueueEntryDto> = emptyList()
    var queueEntryResponse: QueueEntryDto = QueueEntryDto(id = "t-1", status = "WAITING")
    var placeOrdersResponse: OrderPageDto = OrderPageDto()
    var orderResponse: BusinessOrderDto = BusinessOrderDto(id = "o-1", status = "NEW")
    var menuResponse: List<MenuSectionDto> = emptyList()
    var itemResponse: MenuItemDto = MenuItemDto(id = "i-1")
    var barberJournalResponse: AppointmentPageDto = AppointmentPageDto()
    var clinicJournalResponse: AppointmentPageDto = AppointmentPageDto()
    var appointmentResponse: AppointmentDto = AppointmentDto(id = "a-1", status = "PENDING")

    var acceptBody: Map<String, Int>? = null
    var declineBody: Map<String, String>? = null
    var statusBody: Map<String, String>? = null
    var createdItem: CreateMenuItemRequest? = null
    var updatedItem: UpdateMenuItemRequest? = null
    var updatedItemId: String? = null
    var deletedItemId: String? = null

    /** Что ушло в `GET places/{placeId}/orders` — на что тест может сослаться целиком. */
    data class PlaceOrdersRequest(val placeId: String, val vertical: String?, val status: String?)

    var placeOrdersRequest: PlaceOrdersRequest? = null
    var barberJournalRequest: Triple<String, String?, String?>? = null
    var clinicJournalRequest: List<String?>? = null
    var appointmentStatusBody: Map<String, String>? = null
    var clinicAppointmentStatusBody: Map<String, String>? = null

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

    override suspend fun placeOrders(
        placeId: String,
        vertical: String?,
        status: String?,
        page: Int,
        size: Int,
    ): ApiResponse<OrderPageDto> {
        placeOrdersRequest = PlaceOrdersRequest(placeId, vertical, status)
        return ApiResponse(data = placeOrdersResponse)
    }

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

    override suspend fun updateItem(
        itemId: String,
        body: UpdateMenuItemRequest,
    ): ApiResponse<MenuItemDto> {
        updatedItemId = itemId
        updatedItem = body
        return ApiResponse(data = itemResponse)
    }

    /** `ApiResponseVoid`: `data` пустая и при успехе, как у [toggleItem]. */
    override suspend fun deleteItem(itemId: String): ApiResponse<kotlinx.serialization.json.JsonElement> {
        deletedItemId = itemId
        return ApiResponse(success = true)
    }

    override suspend fun barberJournal(
        placeId: String,
        date: String?,
        status: String?,
        page: Int,
        size: Int,
    ): ApiResponse<AppointmentPageDto> {
        barberJournalRequest = Triple(placeId, date, status)
        return ApiResponse(data = barberJournalResponse)
    }

    override suspend fun updateAppointmentStatus(
        appointmentId: String,
        body: Map<String, String>,
    ): ApiResponse<AppointmentDto> {
        appointmentStatusBody = body
        return ApiResponse(data = appointmentResponse)
    }

    override suspend fun clinicJournal(
        placeId: String,
        doctorId: String?,
        date: String?,
        status: String?,
        page: Int,
        size: Int,
    ): ApiResponse<AppointmentPageDto> {
        clinicJournalRequest = listOf(placeId, doctorId, date, status)
        return ApiResponse(data = clinicJournalResponse)
    }

    override suspend fun updateClinicAppointmentStatus(
        placeId: String,
        appointmentId: String,
        body: Map<String, String>,
    ): ApiResponse<AppointmentDto> {
        clinicAppointmentStatusBody = body
        return ApiResponse(data = appointmentResponse)
    }
}

/**
 * `FashionApi` в памяти для `updateStoreOrderStatus` (issue #187) —
 * остальные `DefaultBusinessRepository` не зовёт вовсе, и тестами здесь не
 * нужны.
 */
private class RecordingFashionApi : FashionApi {

    var updateOrderResponse: FashionStoreOrderDto = FashionStoreOrderDto(id = "o-1", status = "NEW")

    var updateStatusBody: Map<String, String>? = null

    override suspend fun categories(): ApiResponse<List<FashionCategoryDto>> =
        error("not used by BusinessRepositoryTest")

    override suspend fun catalog(
        storeId: String,
        categoryId: String?,
        page: Int,
        size: Int,
    ): ApiResponse<CatalogDto> = error("not used by BusinessRepositoryTest")

    override suspend fun product(productId: String): ApiResponse<ProductDetailDto> =
        error("not used by BusinessRepositoryTest")

    override suspend fun cart(): ApiResponse<List<CartItemDto>> =
        error("not used by BusinessRepositoryTest")

    override suspend fun addToCart(body: AddToCartRequestDto): ApiResponse<CartItemDto> =
        error("not used by BusinessRepositoryTest")

    override suspend fun updateCartItem(
        variantId: String,
        quantity: Int,
    ): ApiResponse<kotlinx.serialization.json.JsonElement> =
        error("not used by BusinessRepositoryTest")

    override suspend fun removeCartItem(
        variantId: String,
    ): ApiResponse<kotlinx.serialization.json.JsonElement> =
        error("not used by BusinessRepositoryTest")

    override suspend fun createOrder(body: FashionPlaceOrderRequestDto): ApiResponse<CreatedOrderDto> =
        error("not used by BusinessRepositoryTest")

    override suspend fun myOrders(
        vertical: String?,
        page: Int,
        size: Int,
    ): ApiResponse<OrderPageDto> = error("not used by BusinessRepositoryTest")

    override suspend fun order(orderId: String): ApiResponse<OrderViewDto> =
        error("not used by BusinessRepositoryTest")

    override suspend fun cancelOrder(
        orderId: String,
    ): ApiResponse<kotlinx.serialization.json.JsonElement> =
        error("not used by BusinessRepositoryTest")

    override suspend fun updateStoreOrderStatus(
        storeId: String,
        orderId: String,
        body: Map<String, String>,
    ): ApiResponse<FashionStoreOrderDto> {
        updateStatusBody = body
        return ApiResponse(data = updateOrderResponse)
    }
}

/** `FashionApi` по умолчанию для тестов, которым сама одежда не нужна. */
private class FakeFashionApi : FashionApi by RecordingFashionApi()
