package uz.mahalla.feature.fashion.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartStore
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod

/**
 * Заказы одежды (issue #108) на MockWebServer.
 *
 * Тело `POST fashion/orders` живым запросом не подтвердить — `401` приходит до
 * валидации. Тест закрепляет то, что приложение отправляет **сейчас**, чтобы
 * правка после проверки под токеном была видна одной строкой.
 */
class FashionOrderRepositoryTest {

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

    @Test
    fun `order carries variant ids as item ids`() = runTest {
        server.enqueue(envelope("""{"id":"o-1"}"""))

        val orderId = (
            repository().create(
                store = store(item("v-1", quantity = 2), item("v-2")),
                form = CheckoutForm(
                    method = DeliveryMethod.Delivery,
                    address = " Amir Temur 1 ",
                    payment = PaymentMethod.Wallet,
                ),
            ) as ApiResult.Success
            ).data

        val request = server.takeRequest()
        assertEquals("/fashion/orders", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""placeId":"$STORE""""))
        // В корзине бэкенда строка ключуется вариантом — заказывают
        // конкретный размер конкретного цвета, а не товар.
        assertTrue(body.contains(""""itemId":"v-1""""))
        assertTrue(body.contains(""""quantity":2"""))
        assertTrue(body.contains(""""itemId":"v-2""""))
        assertTrue(body.contains(""""fulfillment":"DELIVERY""""))
        assertTrue(body.contains(""""paymentMethod":"WALLET""""))
        assertTrue(body.contains(""""deliveryAddress":"Amir Temur 1""""))
        assertEquals("o-1", orderId)
    }

    @Test
    fun `pickup order carries no address`() = runTest {
        server.enqueue(envelope("""{"id":"o-1"}"""))

        repository().create(
            store = store(item("v-1")),
            form = CheckoutForm(
                method = DeliveryMethod.Pickup,
                address = "Amir Temur 1",
                payment = PaymentMethod.Cash,
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""fulfillment":"PICKUP""""))
        assertFalse(body.contains("deliveryAddress"))
    }

    @Test
    fun `empty order never reaches the network`() = runTest {
        val result = repository().create(store(), CheckoutForm()) as ApiResult.Failure

        assertEquals(ApiError.Business("FASHION_ORDER_EMPTY"), result.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `order without an id in the response is still an order`() = runTest {
        // В отличие от «Еды», экрана статуса по id здесь нет: заказ найдётся
        // в «моих заказах», и объявлять удачное оформление ошибкой незачем.
        server.enqueue(envelope("""{"createdAt":"2026-09-05T10:00:00"}"""))

        val orderId = (repository().create(store(item("v-1")), CheckoutForm()) as ApiResult.Success)
            .data

        assertEquals("", orderId)
    }

    @Test
    fun `my orders are read from the common controller filtered by vertical`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                     {"id":"o-1","orderNumber":"CL-42","placeId":"$STORE","vertical":"CLOTHING",
                      "status":"ACCEPTED","fulfillment":"DELIVERY","paymentMethod":"WALLET",
                      "itemsAmount":480000,"deliveryAmount":20000,"totalAmount":500000,
                      "createdAt":"2026-09-05T10:00:00",
                      "items":[{"itemType":"VARIANT","itemId":"v-1","itemName":"Oq ko'ylak",
                                "quantity":2,"unitPrice":240000,"totalPrice":480000}]},
                     {"orderNumber":"CL-43"}],
                   "page":0,"totalPages":2,"last":false}""",
            ),
        )

        val page = (repository().myOrders(page = 0) as ApiResult.Success).data

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.startsWith("/orders"))
        assertTrue(path.contains("vertical=CLOTHING"))
        assertTrue(path.contains("page=0"))
        // Заказ без id выбрасывается: отменить его нечем, а в списке это
        // дубликат ключа.
        val order = page.items.single()
        assertEquals("o-1", order.id)
        assertEquals("CL-42", order.number)
        assertEquals(OrderStatus.Confirmed, order.status)
        assertEquals(500_000L, order.totals.totalSum)
        assertEquals("Oq ko'ylak", order.lines.single().name)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе дата пуста у всех.
        assertTrue(order.createdAt != null)
        assertTrue(page.hasMore)
    }

    @Test
    fun `last page stops the paging`() = runTest {
        server.enqueue(envelope("""{"content":[],"page":1,"totalPages":5,"last":true}"""))
        assertFalse((repository().myOrders(page = 1) as ApiResult.Success).data.hasMore)

        // Без `last` смотрим на номер запрошенной страницы: сервер, не
        // вернувший `page`, отдаёт дефолтный `0`.
        server.enqueue(envelope("""{"content":[],"totalPages":3}"""))
        assertTrue((repository().myOrders(page = 1) as ApiResult.Success).data.hasMore)

        server.enqueue(envelope("""{"content":[]}"""))
        assertFalse((repository().myOrders(page = 0) as ApiResult.Success).data.hasMore)
    }

    @Test
    fun `cancelling posts to the fashion controller without a body`() = runTest {
        server.enqueue(voidEnvelope())

        val result = repository().cancel("o-1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/fashion/orders/o-1/cancel", request.path)
        assertEquals(0L, request.bodySize)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `cancelling succeeds even when the response body says nothing`() = runTest {
        // Ответ отмены описан перекрытой коллизией схемой — разбирать из него
        // статус значило бы превращать удачную отмену в «не удалось».
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true,"data":{"whatever":1}}"""),
        )

        assertTrue(repository().cancel("o-1") is ApiResult.Success)
    }

    @Test
    fun `order is re-read from the common endpoint`() = runTest {
        server.enqueue(
            envelope("""{"id":"o-1","status":"CANCELLED","vertical":"CLOTHING"}"""),
        )

        val order = (repository().order("o-1") as ApiResult.Success).data

        assertEquals("/orders/o-1", server.takeRequest().path)
        assertEquals(OrderStatus.Cancelled, order.status)
    }

    @Test
    fun `cancel refusal carries the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"ORDER_ALREADY_SHIPPED",
                       "message":"Buyurtma yo'lga chiqdi"}}""",
                ),
        )

        val failure = (repository().cancel("o-1") as ApiResult.Failure).failure

        assertEquals("Buyurtma yo'lga chiqdi", failure.serverMessage)
    }

    @Test
    fun `my orders without a session answers with the unauthorized the stand sends`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().myOrders() as ApiResult.Failure).failure

        assertEquals(ApiError.Unauthorized, failure.error)
    }

    private fun repository() = DefaultFashionOrderRepository(api = fashionApi(server))

    private fun store(vararg items: FashionCartItem) =
        FashionCartStore(storeId = STORE, items = items.toList())

    private fun item(variantId: String, quantity: Int = 1) = FashionCartItem(
        variantId = variantId,
        storeId = STORE,
        productName = "Ko'ylak",
        unitPriceSum = 240_000,
        quantity = quantity,
    )

    private fun voidEnvelope(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true}""")

    private companion object {
        const val STORE = "11111111-1111-1111-1111-111111111111"
    }
}
