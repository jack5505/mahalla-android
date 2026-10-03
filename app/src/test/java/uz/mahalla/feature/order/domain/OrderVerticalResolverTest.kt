package uz.mahalla.feature.order.domain

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.food.data.FoodApi

/**
 * Резолвер вертикали заказа для deep link'а (issue #343) на настоящем сетевом
 * стеке ([NetworkFactory] + [MockWebServer]): цена ошибки здесь — заказ одежды
 * или аптеки, открытый под видом заказа еды, поэтому разбор JSON-ответа
 * проверяется не фейком, а настоящей схемой.
 */
class OrderVerticalResolverTest {

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
    fun `a food order resolves to the food kind`() = runTest {
        server.enqueue(envelope("""{"id":"o-1","vertical":"FOOD"}"""))

        val result = resolver().resolve("o-1")

        assertEquals("/orders/o-1", server.takeRequest().path)
        assertEquals(ApiResult.Success(ActivityKind.FoodOrder), result)
    }

    @Test
    fun `a clothing order resolves to the clothing kind`() = runTest {
        server.enqueue(envelope("""{"id":"o-1","vertical":"CLOTHING"}"""))

        assertEquals(ApiResult.Success(ActivityKind.ClothingOrder), resolver().resolve("o-1"))
    }

    @Test
    fun `a pharmacy order resolves to the pharmacy kind`() = runTest {
        server.enqueue(envelope("""{"id":"o-1","vertical":"PHARMACY"}"""))

        assertEquals(ApiResult.Success(ActivityKind.PharmacyOrder), resolver().resolve("o-1"))
    }

    @Test
    fun `a vertical the client does not know yet is not hidden as food`() {
        runTest {
            server.enqueue(envelope("""{"id":"o-1","vertical":"SOME_NEW_VERTICAL"}"""))

            assertEquals(ApiResult.Success(ActivityKind.OtherOrder), resolver().resolve("o-1"))
        }
    }

    @Test
    fun `a missing order is a failure, not a guess`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = resolver().resolve("o-404")

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
    }

    private fun resolver(): OrderVerticalResolver = DefaultOrderVerticalResolver(foodApi = api())

    private fun api(): FoodApi = NetworkFactory
        .retrofit(
            server.url("/").toString(),
            NetworkFactory.clientBuilder().build(),
            NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        .create(FoodApi::class.java)

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
