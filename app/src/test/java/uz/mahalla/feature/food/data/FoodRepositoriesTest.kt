package uz.mahalla.feature.food.data

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
import uz.mahalla.feature.food.domain.PromoKind
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.cartLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.util.Locale

/**
 * Репозитории вертикали «Еда» (эпик 5) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
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
    fun `menu is mapped with categories, options and the stop list`() = runTest {
        server.enqueue(json(MENU_BODY))

        val menu = (menuRepository().menu("place-1") as ApiResult.Success).data

        assertEquals("/places/place-1/menu", server.takeRequest().path)
        assertEquals(listOf("main"), menu.categories.map { it.id })
        val items = menu.categories.single().items
        assertEquals(listOf("osh", "somsa"), items.map(MenuItem::id))
        assertFalse(items.last().isAvailable)
        assertEquals(15_000L, menu.deliverySum)
        assertEquals(listOf("size"), items.first().optionGroups.map { it.id })
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
    fun `option group bounds are clamped to something achievable`() = runTest {
        // maxChoices = 0 сделал бы группу невыполнимой, и кнопка «добавить»
        // не включилась бы никогда.
        server.enqueue(json(BAD_BOUNDS_MENU_BODY))

        val group = (menuRepository().menu("place-1") as ApiResult.Success)
            .data.categories.single().items.single().optionGroups.single()

        assertEquals(1, group.maxChoices)
        assertEquals(1, group.minChoices)
    }

    @Test
    fun `promo is checked on the server with the current subtotal`() = runTest {
        server.enqueue(json(PROMO_BODY))

        val promo = (menuRepository().promo("place-1", " mahalla10 ", 90_000) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/places/place-1/promo", request.path)
        val body = request.body.readUtf8()
        // Код нормализуется до отправки: сервер не обязан знать про пробелы и
        // регистр, которые набрал человек.
        assertTrue(body, body.contains("\"code\":\"MAHALLA10\""))
        assertTrue(body, body.contains("\"subtotal\":90000"))
        assertEquals(PromoKind.Percent, promo.kind)
        assertEquals(10L, promo.value)
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

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"code\":\"MAHALLI\""))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `an unknown promo kind is treated as a fixed amount`() = runTest {
        // Процент от неизвестного правила разошёлся бы с чеком.
        server.enqueue(json("""{"code":"X","kind":"magic","value":5000}"""))

        val promo = (menuRepository().promo("place-1", "X", 50_000) as ApiResult.Success).data

        assertEquals(PromoKind.Fixed, promo.kind)
    }

    // --- Заказы ---

    @Test
    fun `creating an order sends the cart and clears the draft`() = runTest {
        server.enqueue(json(ORDER_BODY))
        val cart = FakeCartRepository()
        val dao = FakeOrderDao()

        val result = orderRepository(cart, dao).create(
            cart = Cart(
                placeId = "place-1",
                placeName = "Osh markazi",
                lines = listOf(cartLine("osh", quantity = 2, optionIds = setOf("large"))),
            ),
            form = CheckoutForm(
                method = DeliveryMethod.Delivery,
                address = "  Amir Temur 1  ",
                comment = " tez ",
                payment = PaymentMethod.Wallet,
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"placeId\":\"place-1\""))
        assertTrue(body, body.contains("\"quantity\":2"))
        assertTrue(body, body.contains("\"method\":\"delivery\""))
        assertTrue(body, body.contains("\"address\":\"Amir Temur 1\""))
        assertEquals(OrderStatus.Created, (result as ApiResult.Success).data.status)
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
    fun `an asap order sends no scheduled time`() = runTest {
        server.enqueue(json(ORDER_BODY))

        orderRepository().create(
            cart = Cart("place-1", "Osh markazi", lines = listOf(cartLine("osh"))),
            form = CheckoutForm(
                method = DeliveryMethod.Pickup,
                asap = true,
                scheduledAt = LocalDateTime.of(2026, 8, 26, 19, 0),
            ),
        )

        assertFalse(server.takeRequest().body.readUtf8().contains("scheduledAt"))
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
    fun `cancelling returns the updated order`() = runTest {
        server.enqueue(json(CANCELLED_ORDER_BODY))

        val result = orderRepository().cancel("o-1")

        assertEquals("/orders/o-1/cancel", server.takeRequest().path)
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

    // --- Кошелёк ---

    @Test
    fun `wallet balance is read as a whole number of sums`() = runTest {
        server.enqueue(json("""{"balance":250000}"""))

        assertEquals(250_000L, (walletRepository().balance() as ApiResult.Success).data)
    }

    @Test
    fun `a negative balance from the server is clamped to zero`() = runTest {
        // Отрицательный баланс — ошибка сервера; показывать «−5 000» человеку
        // незачем, а на проверку «хватает ли денег» он влияет одинаково.
        server.enqueue(json("""{"balance":-5000}"""))

        assertEquals(0L, (walletRepository().balance() as ApiResult.Success).data)
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
    ) = DefaultOrderRepository(api(), dao, cart)

    private fun walletRepository() = DefaultWalletRepository(api())

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
            {"placeName":"Osh markazi","deliveryFee":15000,"categories":[
              {"id":"main","name":"Asosiy","items":[
                {"id":"osh","name":"Osh","price":30000,"available":true,
                 "optionGroups":[{"id":"size","name":"Hajmi","minChoices":1,"maxChoices":1,
                   "options":[{"id":"small","name":"Kichik"},
                              {"id":"large","name":"Katta","priceDelta":10000}]}]},
                {"id":"somsa","name":"Somsa","price":12000,"available":false}
              ]}
            ]}
        """

        const val BROKEN_MENU_BODY = """
            {"categories":[{"id":"main","name":"Asosiy","items":[
              {"id":"osh","name":"Osh","price":30000},
              {"id":"","name":"","price":1000}
            ]}]}
        """

        const val BAD_BOUNDS_MENU_BODY = """
            {"categories":[{"id":"main","name":"Asosiy","items":[
              {"id":"osh","name":"Osh","price":30000,
               "optionGroups":[{"id":"size","name":"Hajmi","minChoices":5,"maxChoices":0,
                 "options":[{"id":"small","name":"Kichik"}]}]}
            ]}]}
        """

        const val PROMO_BODY = """{"code":"MAHALLA10","kind":"percent","value":10,"minOrder":50000}"""

        const val ORDER_BODY = """
            {"id":"o-1","placeId":"place-1","placeName":"Osh markazi","status":"created",
             "method":"delivery","payment":"wallet","subtotal":60000,"discount":0,"delivery":15000,
             "items":[{"itemId":"osh","name":"Osh","price":30000,"quantity":2}],
             "createdAt":1774000000,"etaMinutes":40}
        """

        const val CANCELLED_ORDER_BODY = """
            {"id":"o-1","placeId":"place-1","placeName":"Osh markazi","status":"cancelled",
             "method":"delivery","payment":"wallet","subtotal":60000,"createdAt":1774000000}
        """
    }
}
