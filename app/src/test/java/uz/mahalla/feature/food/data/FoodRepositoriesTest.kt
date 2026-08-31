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
import uz.mahalla.data.db.entity.PlaceEntity
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakePlaceDao
import uz.mahalla.testutil.cartLine
import java.time.Instant
import java.util.Locale

/**
 * Репозитории вертикали «Еда» (эпик 5) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Тела ответов — из контракта стенда (issue #63): пути под `food`, конверт
 * `{success, data, error}`, суммы отдельными полями.
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
    fun `menu comes from the food path and keeps the stop list`() = runTest {
        server.enqueue(json(MENU_BODY))

        val menu = (menuRepository().menu("place-1") as ApiResult.Success).data

        assertEquals("/food/places/place-1/menu", server.takeRequest().path)
        // «Меню» бэкенда — это категория приложения.
        assertEquals(listOf("m-1"), menu.categories.map { it.id })
        val items = menu.categories.single().items
        assertEquals(listOf("osh", "somsa"), items.map(MenuItem::id))
        assertFalse(items.last().isAvailable)
        assertEquals(30_000L, items.first().priceSum)
    }

    @Test
    fun `the place name comes from the catalog cache, not from the menu`() = runTest {
        // В `MenuResponse` названия заведения нет вовсе, а шапка меню и корзина
        // его показывают.
        server.enqueue(json(MENU_BODY))
        val places = FakePlaceDao().apply { seed(listOf(place("place-1", "Osh markazi"))) }

        val menu = (menuRepository(places).menu("place-1") as ApiResult.Success).data

        assertEquals("Osh markazi", menu.placeName)
    }

    @Test
    fun `an unknown place gives an empty menu rather than a crash`() = runTest {
        // Стенд отвечает на неизвестное заведение `{"success":true,"data":[]}`,
        // а не 404 — проверено curl'ом.
        server.enqueue(json("""{"success":true,"data":[]}"""))

        val menu = (menuRepository().menu("place-404") as ApiResult.Success).data

        assertTrue(menu.categories.isEmpty())
        assertTrue(menu.isEmpty)
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
    fun `an envelope with success false is a failure, not an empty menu`() = runTest {
        server.enqueue(json("""{"success":false,"error":{"code":"PLACE_BLOCKED"}}"""))

        val result = menuRepository().menu("place-1")

        assertEquals(ApiError.Business("PLACE_BLOCKED"), (result as ApiResult.Failure).error)
    }

    // --- Промокод ---

    @Test
    fun `promo is checked on the server with the current subtotal`() = runTest {
        server.enqueue(json(PROMO_BODY))

        val promo = (menuRepository().promo("place-1", " mahalla10 ", 90_000) as ApiResult.Success).data

        val request = server.takeRequest()
        // Промокод живёт в общем модуле акций, а не в «Еде».
        assertEquals(
            "/promotions/check?code=MAHALLA10&placeId=place-1&orderAmount=90000",
            request.path,
        )
        assertEquals(9_000L, promo?.discountSum)
        // Скидка привязана к сумме, с которой её считали.
        assertEquals(90_000L, promo?.checkedSubtotalSum)
    }

    @Test
    fun `the promo code is upper-cased by root rules, not by the device locale`() = runTest {
        // На турецкой локали `i` уходит в `İ`, и правильный код улетел бы на
        // сервер испорченным.
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            server.enqueue(json(PROMO_BODY))

            menuRepository().promo("place-1", "mahalli", 90_000)

            assertTrue(server.takeRequest().path!!.contains("code=MAHALLI"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a code that does not fit the order is not applied`() = runTest {
        // `valid: false` со скидкой 0 — «код есть, но не подходит»; применить
        // его молча значит показать скидку 0 без объяснения.
        server.enqueue(json("""{"success":true,"data":{"valid":false,"discountAmount":0}}"""))

        val result = menuRepository().promo("place-1", "X", 50_000)

        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `an unknown code is a not found failure`() = runTest {
        // Ровно то, что отвечает стенд: 404 с кодом NOT_FOUND.
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"NOT_FOUND","message":"Promo-kod topilmadi"}}"""),
        )

        val result = menuRepository().promo("place-1", "X", 50_000)

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
        assertEquals("NOT_FOUND", result.failure.server?.code)
    }

    // --- Заказы ---

    @Test
    fun `creating an order sends only what the contract accepts`() = runTest {
        server.enqueue(json(ORDER_BODY))
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
        val order = (result as ApiResult.Success).data
        assertEquals(OrderStatus.Created, order.status)
        assertEquals("A-1042", order.orderNumber)
        // Итог — число сервера, а не сумма позиций, посчитанная приложением.
        assertEquals(75_000L, order.totalSum)
        assertEquals(15_000L, order.totals.deliverySum)
        assertEquals(Instant.parse("2026-08-26T10:00:00Z"), order.createdAt)
        // Оставленный черновик дал бы оформить тот же заказ второй раз по
        // кнопке «назад».
        assertEquals(listOf("place-1"), cart.clearedPlaceIds)
        assertEquals(listOf("o-1"), dao.rows.value.map(OrderEntity::id))
    }

    @Test
    fun `pickup sends no address even if one was typed earlier`() = runTest {
        server.enqueue(json(ORDER_BODY))

        orderRepository().create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(method = DeliveryMethod.Pickup, address = "Amir Temur 1"),
        )

        assertFalse(server.takeRequest().body.readUtf8().contains("Amir Temur"))
    }

    @Test
    fun `a date without a zone is still understood`() = runTest {
        // Jackson на бэкенде отдаёт `LocalDateTime`; иначе дата пуста у всех.
        server.enqueue(json(ORDER_BODY.replace("2026-08-26T10:00:00Z", "2026-08-26T10:00:00")))

        val order = (orderRepository().order("o-1") as ApiResult.Success).data

        assertEquals(Instant.parse("2026-08-26T10:00:00Z"), order.createdAt)
    }

    @Test
    fun `the order takes the place name from the catalog cache`() = runTest {
        // `OrderResponse` названия заведения не содержит.
        server.enqueue(json(ORDER_BODY))
        val places = FakePlaceDao().apply { seed(listOf(place("place-1", "Osh markazi"))) }

        val order = (orderRepository(places = places).order("o-1") as ApiResult.Success).data

        assertEquals("Osh markazi", order.placeName)
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
    fun `the order is read and cancelled on the food paths`() = runTest {
        server.enqueue(json(ORDER_BODY))
        server.enqueue(json(CANCELLED_ORDER_BODY))
        val repository = orderRepository()

        repository.order("o-1")
        val result = repository.cancel("o-1")

        assertEquals("/food/orders/o-1", server.takeRequest().path)
        val cancel = server.takeRequest()
        assertEquals("/food/orders/o-1/cancel", cancel.path)
        assertEquals("POST", cancel.method)
        assertEquals(OrderStatus.Cancelled, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `repeating puts the lines of the order back into the cart`() = runTest {
        server.enqueue(json(ORDER_BODY))
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
        server.enqueue(json(ORDER_BODY))
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
        server.enqueue(json(ORDER_BODY))
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

    private fun menuRepository(places: FakePlaceDao = FakePlaceDao()) =
        DefaultMenuRepository(api(), places)

    private fun orderRepository(
        cart: FakeCartRepository = FakeCartRepository(),
        dao: FakeOrderDao = FakeOrderDao(),
        places: FakePlaceDao = FakePlaceDao(),
    ) = DefaultOrderRepository(api(), dao, places, cart)

    private fun place(id: String, name: String) = PlaceEntity(
        id = id,
        name = name,
        category = "FOOD",
        rating = 4.5,
        distanceMeters = 100,
        isOpenNow = true,
        updatedAtEpochSeconds = 0,
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
              {"id":"m-1","name":"Asosiy","items":[
                {"id":"osh","name":"Osh","price":30000,"isAvailable":true,"prepMinutes":20},
                {"id":"somsa","name":"Somsa","price":12000,"isAvailable":false}
              ]}
            ]}
        """

        const val BROKEN_MENU_BODY = """
            {"success":true,"data":[{"id":"m-1","name":"Asosiy","items":[
              {"id":"osh","name":"Osh","price":30000},
              {"id":"","name":"","price":1000}
            ]}]}
        """

        const val PROMO_BODY = """
            {"success":true,"data":{"valid":true,"discountAmount":9000,
             "finalAmount":81000,"promoCode":"MAHALLA10"}}
        """

        const val ORDER_BODY = """
            {"success":true,"data":{"id":"o-1","placeId":"place-1","orderNumber":"A-1042",
             "status":"NEW","fulfillment":"DELIVERY","paymentMethod":"WALLET",
             "itemsAmount":60000,"deliveryAmount":15000,"discountAmount":0,"totalAmount":75000,
             "items":[{"itemId":"osh","itemName":"Osh","unitPrice":30000,"quantity":2,
                       "totalPrice":60000}],
             "createdAt":"2026-08-26T10:00:00Z"}}
        """

        const val CANCELLED_ORDER_BODY = """
            {"success":true,"data":{"id":"o-1","placeId":"place-1","status":"CANCELLED",
             "fulfillment":"DELIVERY","paymentMethod":"WALLET","itemsAmount":60000,
             "totalAmount":60000,"createdAt":"2026-08-26T10:00:00Z"}}
        """
    }
}
