package uz.mahalla.feature.food.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.cartLine
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Репозитории вертикали «Еда» (эпик 5) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Тела ответов — с живого стенда (`/v3/api-docs` + curl'ы): конверт
 * `{success, data}`, список «меню» вместо категорий, `OrderView` со суммами.
 */
class FoodRepositoriesTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Меню ---

    @Test
    fun `menu is mapped from the envelope with categories and the stop list`() = runTest {
        server.enqueue(json(MENU_BODY))

        val menu = (menuRepository().menu("place-1") as ApiResult.Success).data

        assertEquals("/food/places/place-1/menu", server.takeRequest().path)
        assertEquals(listOf("m-1"), menu.categories.map { it.id })
        val items = menu.categories.single().items
        assertEquals(listOf("osh", "somsa"), items.map(MenuItem::id))
        assertEquals(30_000L, items.first().priceSum)
        assertFalse(items.last().isAvailable)
    }

    @Test
    fun `the stop list flag is read under both names jackson uses`() = runTest {
        // `boolean isAvailable` уезжает то как `isAvailable`, то как
        // `available`; ошибка здесь увела бы в стоп-лист всё меню.
        server.enqueue(json(AVAILABILITY_BODY))

        val items = (menuRepository().menu("place-1") as ApiResult.Success)
            .data.categories.single().items

        assertEquals(listOf(false, false, true), items.map(MenuItem::isAvailable))
    }

    @Test
    fun `a broken item does not break the whole menu`() = runTest {
        // Позицию без id положить в корзину нечем, но остальное меню обязано
        // доехать.
        server.enqueue(json(BROKEN_MENU_BODY))

        val menu = (menuRepository().menu("place-1") as ApiResult.Success).data

        assertEquals(listOf("osh"), menu.categories.single().items.map(MenuItem::id))
    }

    @Test
    fun `an empty catalog of the place is an empty menu, not an error`() = runTest {
        // Именно так стенд и отвечает, пока меню не заполнено.
        server.enqueue(json("""{"success":true,"data":[]}"""))

        val menu = (menuRepository().menu("place-1") as ApiResult.Success).data

        assertTrue(menu.isEmpty)
    }

    @Test
    fun `a success false envelope is a failure, not an empty menu`() = runTest {
        server.enqueue(
            json("""{"success":false,"error":{"code":"PLACE_NOT_FOUND","message":"Joy topilmadi"}}"""),
        )

        val result = menuRepository().menu("place-1")

        assertEquals(ApiError.Business("PLACE_NOT_FOUND"), (result as ApiResult.Failure).error)
        assertEquals("Joy topilmadi", result.failure.server?.message)
    }

    // --- Заказы ---

    @Test
    fun `creating an order sends what the backend accepts and clears the draft`() = runTest {
        server.enqueue(json(CREATED_ORDER_BODY))
        val cart = FakeCartRepository()
        val dao = FakeOrderDao()

        val result = orderRepository(cart, dao).create(
            cart = Cart(
                placeId = "place-1",
                placeName = "Osh markazi",
                lines = listOf(cartLine("osh", quantity = 2)),
            ),
            form = CheckoutForm(
                method = DeliveryMethod.Delivery,
                address = "  Amir Temur 1  ",
                payment = PaymentMethod.Wallet,
            ),
        )

        val request = server.takeRequest()
        assertEquals("/food/orders", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"placeId\":\"place-1\""))
        assertTrue(body, body.contains("\"itemId\":\"osh\""))
        assertTrue(body, body.contains("\"quantity\":2"))
        assertTrue(body, body.contains("\"fulfillment\":\"DELIVERY\""))
        assertTrue(body, body.contains("\"paymentMethod\":\"WALLET\""))
        assertTrue(body, body.contains("\"deliveryAddress\":\"Amir Temur 1\""))
        assertEquals("o-1", (result as ApiResult.Success).data)
        // Оставленный черновик дал бы оформить тот же заказ второй раз по
        // кнопке «назад».
        assertEquals(listOf("place-1"), cart.clearedPlaceIds)
        // Имя заведения помнит только корзина — в ответах о заказе его нет.
        assertEquals("Osh markazi", dao.byId("o-1")?.placeName)
    }

    @Test
    fun `the order request carries neither price nor options nor a comment`() = runTest {
        // Цену считает сервер; модификаторов и комментария контракт не знает,
        // и отправлять их — врать самому себе в тестах.
        server.enqueue(json(CREATED_ORDER_BODY))

        orderRepository().create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(method = DeliveryMethod.Pickup),
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("price"))
        assertFalse(body, body.contains("optionIds"))
        assertFalse(body, body.contains("comment"))
    }

    @Test
    fun `pickup sends no address even if one was typed earlier`() = runTest {
        server.enqueue(json(CREATED_ORDER_BODY))

        orderRepository().create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(method = DeliveryMethod.Pickup, address = "Amir Temur 1"),
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("Amir Temur"))
        assertTrue(body, body.contains("\"fulfillment\":\"PICKUP\""))
    }

    @Test
    fun `an order response without an id is a failure and keeps the draft`() = runTest {
        // Показать экран статуса нечем; черновик — единственное, что останется
        // у человека.
        server.enqueue(json("""{"success":true,"data":{"status":"NEW"}}"""))
        val cart = FakeCartRepository()

        val result = orderRepository(cart).create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(method = DeliveryMethod.Pickup),
        )

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
        assertEquals(emptyList<String>(), cart.clearedPlaceIds)
    }

    @Test
    fun `a failed order keeps the draft`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val cart = FakeCartRepository()

        val result = orderRepository(cart).create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(method = DeliveryMethod.Pickup),
        )

        assertTrue(result is ApiResult.Failure)
        // Иначе неудачная попытка стоила бы человеку всей собранной корзины.
        assertEquals(emptyList<String>(), cart.clearedPlaceIds)
    }

    @Test
    fun `the order is read from the shared order view with all the sums`() = runTest {
        server.enqueue(json(ORDER_VIEW_BODY))
        val dao = FakeOrderDao()
        dao.upsert(listOf(cachedOrder()))

        val order = (orderRepository(dao = dao).order("o-1") as ApiResult.Success).data

        assertEquals("/orders/o-1", server.takeRequest().path)
        assertEquals(OrderStatus.Preparing, order.status)
        assertEquals(DeliveryMethod.Delivery, order.method)
        assertEquals(PaymentMethod.Cash, order.payment)
        assertEquals("F-42", order.number)
        assertEquals(60_000L, order.totals.subtotalSum)
        assertEquals(15_000L, order.totals.deliverySum)
        assertEquals(5_000L, order.totals.discountSum)
        assertEquals(listOf("osh"), order.lines.map(CartLine::itemId))
        assertEquals(2, order.lines.single().quantity)
        // Дату Jackson отдаёт без зоны — иначе она была бы пуста у всех.
        assertEquals(Instant.parse("2026-08-26T10:00:00Z"), order.createdAt)
        // Имя заведения подставляется из кэша: в `OrderView` его нет.
        assertEquals("Osh markazi", order.placeName)
    }

    @Test
    fun `an order line without a unit price falls back to the line total`() = runTest {
        server.enqueue(json(NO_UNIT_PRICE_BODY))

        val line = (orderRepository().order("o-1") as ApiResult.Success).data.lines.single()

        assertEquals(30_000L, line.unitPriceSum)
    }

    @Test
    fun `cancelling reports success without guessing the response fields`() = runTest {
        // Ответ отмены описан схемой, перекрытой коллизией springdoc: тело не
        // разбирается вовсе, новое состояние читает `order()`.
        server.enqueue(json("""{"success":true,"data":{"id":"o-1","status":"CANCELLED"}}"""))

        val result = orderRepository().cancel("o-1")

        assertEquals("/food/orders/o-1/cancel", server.takeRequest().path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `a refused cancel is a failure with the message of the server`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"ORDER_IN_PROGRESS","message":"Buyurtma tayyorlanmoqda"}}"""),
        )

        val result = orderRepository().cancel("o-1")

        assertEquals("Buyurtma tayyorlanmoqda", (result as ApiResult.Failure).failure.server?.message)
    }

    @Test
    fun `repeating puts the lines of the order back into the cart`() = runTest {
        server.enqueue(json(ORDER_VIEW_BODY))
        val cart = FakeCartRepository()
        val repository = orderRepository(cart)
        val order = (repository.order("o-1") as ApiResult.Success).data

        repository.repeat(order)

        val lines = cart.current("place-1").lines
        assertEquals(listOf("osh"), lines.map(CartLine::itemId))
        assertEquals(2, lines.single().quantity)
    }

    @Test
    fun `repeating replaces the draft of another place, not merges into it`() = runTest {
        server.enqueue(json(ORDER_VIEW_BODY))
        val cart = FakeCartRepository()
        cart.seed(Cart("place-2", "Boshqa joy", lines = listOf(cartLine("lagman"))))
        val repository = orderRepository(cart)
        val order = (repository.order("o-1") as ApiResult.Success).data

        repository.repeat(order)

        assertTrue(cart.current("place-2").isEmpty)
        assertEquals(listOf("osh"), cart.current("place-1").lines.map(CartLine::itemId))
    }

    @Test
    fun `a failed repeat keeps the current draft and reports the failure`() = runTest {
        // Раньше корзина чистилась до добавления: падение посередине оставляло
        // человека и без прежнего черновика, и без нового.
        server.enqueue(json(ORDER_VIEW_BODY))
        val cart = FakeCartRepository()
        cart.seed(Cart("place-2", "Boshqa joy", lines = listOf(cartLine("lagman"))))
        val repository = orderRepository(cart)
        val order = (repository.order("o-1") as ApiResult.Success).data
        cart.replaceFails = true

        assertNull(repository.repeat(order))
        assertEquals(listOf("lagman"), cart.current("place-2").lines.map(CartLine::itemId))
    }

    @Test
    fun `a missing order is an error, not an empty screen`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = orderRepository().order("o-404")

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
    }

    private fun api(): FoodApi = NetworkFactory
        .retrofit(
            server.url("/").toString(),
            NetworkFactory.clientBuilder().build(),
            NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        .create(FoodApi::class.java)

    private fun menuRepository() = DefaultMenuRepository(api())

    private fun orderRepository(
        cart: FakeCartRepository = FakeCartRepository(),
        dao: FakeOrderDao = FakeOrderDao(),
    ) = DefaultOrderRepository(
        api = api(),
        orderDao = dao,
        cartRepository = cart,
        clock = Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC),
    )

    private fun cachedOrder(): OrderEntity = OrderEntity(
        id = "o-1",
        placeId = "place-1",
        placeName = "Osh markazi",
        status = OrderStatus.Created.apiValue,
        totalSum = 60_000,
        createdAtEpochSeconds = 0,
    )

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    /** Кэш заказов проверяется в `MahallaDatabaseTest`; здесь достаточно факта записи. */
    private class FakeOrderDao : OrderDao {

        val rows = MutableStateFlow<List<OrderEntity>>(emptyList())

        override fun observeAll(): Flow<List<OrderEntity>> = rows

        override suspend fun byId(id: String): OrderEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(orders: List<OrderEntity>) {
            rows.value = (rows.value.filterNot { row -> orders.any { it.id == row.id } }) + orders
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private companion object {
        const val MENU_BODY = """
            {"success":true,"data":[
              {"id":"m-1","name":"Asosiy","description":"Issiq taomlar","items":[
                {"id":"osh","name":"Osh","description":"Toshkent oshi","price":30000,
                 "prepMinutes":20,"isAvailable":true,"isHalal":true},
                {"id":"somsa","name":"Somsa","price":12000,"isAvailable":false}
              ]}
            ]}
        """

        const val AVAILABILITY_BODY = """
            {"success":true,"data":[{"id":"m-1","name":"Asosiy","items":[
              {"id":"a","name":"A","price":1000,"isAvailable":false},
              {"id":"b","name":"B","price":1000,"available":false},
              {"id":"c","name":"C","price":1000}
            ]}]}
        """

        const val BROKEN_MENU_BODY = """
            {"success":true,"data":[{"id":"m-1","name":"Asosiy","items":[
              {"id":"osh","name":"Osh","price":30000},
              {"id":"","name":"","price":1000},
              {"name":"Nomsiz","price":1000}
            ]}]}
        """

        const val CREATED_ORDER_BODY = """{"success":true,"data":{"id":"o-1","status":"NEW"}}"""

        const val ORDER_VIEW_BODY = """
            {"success":true,"data":{"id":"o-1","orderNumber":"F-42","placeId":"place-1",
             "vertical":"FOOD","status":"PREPARING","fulfillment":"DELIVERY","paymentMethod":"CASH",
             "itemsAmount":60000,"deliveryAmount":15000,"discountAmount":5000,"totalAmount":70000,
             "deliveryAddress":"Amir Temur 1","createdAt":"2026-08-26T10:00:00",
             "items":[{"itemType":"MENU_ITEM","itemId":"osh","itemName":"Osh","quantity":2,
                       "unitPrice":30000,"totalPrice":60000}]}}
        """

        const val NO_UNIT_PRICE_BODY = """
            {"success":true,"data":{"id":"o-1","placeId":"place-1","status":"NEW",
             "items":[{"itemId":"osh","itemName":"Osh","quantity":2,"totalPrice":60000}]}}
        """
    }
}
