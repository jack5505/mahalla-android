package uz.mahalla.feature.fashion.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules

/**
 * Серверная корзина одежды (issue #108) на MockWebServer.
 *
 * Все четыре ручки требуют Bearer (`401` без него — проверено на стенде), а
 * количество у `PUT` идёт **query-параметром**: ошибка здесь молча оставила бы
 * прежнее количество.
 */
class FashionCartRepositoryTest {

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
    fun `cart is parsed with its lines and store split`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"i-1","variantId":"v-1","storeId":"s-1","productName":"Oq ko'ylak",
                     "colorName":"Oq","size":"M","unitPrice":240000,"quantity":2,
                     "totalPrice":480000},
                   {"id":"i-2","variantId":"v-2","storeId":"s-2","productName":"Shim",
                     "unitPrice":410000,"quantity":1}]""",
            ),
        )

        val cart = (repository().cart() as ApiResult.Success).data

        assertEquals("/fashion/cart", server.takeRequest().path)
        assertEquals(listOf("v-1", "v-2"), cart.items.map(FashionCartItem::variantId))
        assertEquals(480_000L, cart.item("v-1")?.totalSum)
        // Сервер не назвал сумму строки — считаем из цены за единицу.
        assertEquals(410_000L, cart.item("v-2")?.totalSum)
        assertEquals(2, cart.stores.size)
    }

    @Test
    fun `a line without a variant id is dropped`() = runTest {
        // Ни изменить, ни удалить, ни заказать её нельзя: `variantId` —
        // единственный ключ строки во всех трёх ручках.
        server.enqueue(
            envelope(
                """[{"id":"i-1","variantId":"v-1","storeId":"s-1","unitPrice":100,"quantity":1},
                   {"id":"i-2","storeId":"s-1","productName":"Sirli tovar","quantity":1}]""",
            ),
        )

        val cart = (repository().cart() as ApiResult.Success).data

        assertEquals(listOf("v-1"), cart.items.map(FashionCartItem::variantId))
    }

    @Test
    fun `zero quantity from the server is read as one`() = runTest {
        server.enqueue(
            envelope("""[{"variantId":"v-1","storeId":"s-1","unitPrice":100,"quantity":0}]"""),
        )

        val cart = (repository().cart() as ApiResult.Success).data

        assertEquals(1, cart.items.single().quantity)
    }

    @Test
    fun `adding sends the variant and the quantity in the body`() = runTest {
        server.enqueue(
            envelope("""{"id":"i-1","variantId":"v-1","storeId":"s-1","quantity":2}"""),
        )

        val added = (repository().add("v-1", quantity = 2) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/fashion/cart/add", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""variantId":"v-1""""))
        assertTrue(body.contains(""""quantity":2"""))
        assertEquals("v-1", added.variantId)
    }

    @Test
    fun `adding clamps the quantity before it reaches the network`() = runTest {
        server.enqueue(envelope("""{"variantId":"v-1","storeId":"s-1","quantity":99}"""))

        repository().add("v-1", quantity = FashionCartRules.MAX_QUANTITY + 10)

        assertTrue(server.takeRequest().body.readUtf8().contains(""""quantity":99"""))
    }

    @Test
    fun `an odd add response is not a failure`() = runTest {
        // Товар уже в корзине — сказать «не добавилось» о добавленном значит
        // заставить нажать второй раз.
        server.enqueue(envelope("""{"id":"i-1"}"""))

        val added = (repository().add("v-1") as ApiResult.Success).data

        assertEquals("v-1", added.variantId)
        assertEquals(1, added.quantity)
    }

    @Test
    fun `quantity update goes into the query, not the body`() = runTest {
        server.enqueue(voidEnvelope())

        repository().setQuantity("v-1", 5)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/fashion/cart/v-1?quantity=5", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `quantity below one never reaches the server as zero`() = runTest {
        // У удаления своя ручка, а что сделает бэкенд с `quantity=0`, из
        // контракта не следует.
        server.enqueue(voidEnvelope())

        repository().setQuantity("v-1", 0)

        assertEquals("/fashion/cart/v-1?quantity=1", server.takeRequest().path)
    }

    @Test
    fun `removal addresses the variant`() = runTest {
        server.enqueue(voidEnvelope())

        val result = repository().remove("v-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/fashion/cart/v-1", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `envelope with success false fails even on a 200`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"OUT_OF_STOCK",
                       "message":"Bu o'lcham qolmagan"}}""",
                ),
        )

        val failure = (repository().add("v-1") as ApiResult.Failure).failure

        assertEquals(ApiError.Business("OUT_OF_STOCK"), failure.error)
        assertEquals("Bu o'lcham qolmagan", failure.serverMessage)
    }

    @Test
    fun `cart without a session answers with the unauthorized the stand sends`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().cart() as ApiResult.Failure).failure

        assertEquals(ApiError.Unauthorized, failure.error)
        assertEquals("Kirish uchun autentifikatsiya talab qilinadi", failure.serverMessage)
    }

    private fun repository() = DefaultFashionCartRepository(api = fashionApi(server))

    /** `ApiResponseVoid`: успех без полезной нагрузки. */
    private fun voidEnvelope(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true}""")
}
