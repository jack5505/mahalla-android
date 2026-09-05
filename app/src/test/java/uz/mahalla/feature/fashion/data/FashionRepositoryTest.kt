package uz.mahalla.feature.fashion.data

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
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.fashion.domain.ProductGender
import uz.mahalla.feature.fashion.domain.ProductVariant

/**
 * Каталог одежды (issue #108) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-05: категории и каталог отвечают `200` без
 * токена, неизвестный товар — `404`, а `storeId` обязан быть uuid.
 */
class FashionRepositoryTest {

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
    fun `categories are parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"c-1","name":"Ko'ylaklar","iconUrl":"https://cdn/1.png"},
                   {"id":"c-2","name":"Shimlar"}]""",
            ),
        )

        val categories = (repository().categories() as ApiResult.Success).data

        assertEquals("/fashion/categories", server.takeRequest().path)
        assertEquals(listOf("c-1", "c-2"), categories.map { it.id })
        assertEquals("https://cdn/1.png", categories.first().iconUrl)
        assertNull(categories.last().iconUrl)
    }

    @Test
    fun `a category that cannot be selected is dropped`() = runTest {
        // Без id по нему не отфильтровать, без имени чип пуст — оба варианта
        // читаются как сломанный фильтр.
        server.enqueue(
            envelope("""[{"id":"c-1","name":"Ko'ylaklar"},{"name":"Nomsiz"},{"id":"c-3"}]"""),
        )

        val categories = (repository().categories() as ApiResult.Success).data

        assertEquals(listOf("c-1"), categories.map { it.id })
    }

    @Test
    fun `catalog is requested by store with paging and optional category`() = runTest {
        server.enqueue(envelope("""{"products":[],"page":0,"totalPages":0,"totalElements":0}"""))

        repository().catalog(storeId = STORE, categoryId = "c-1", page = 2, size = 20)

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.startsWith("/fashion/stores/$STORE/catalog"))
        assertTrue(path.contains("categoryId=c-1"))
        assertTrue(path.contains("page=2"))
        assertTrue(path.contains("size=20"))
    }

    @Test
    fun `blank category is not sent at all`() = runTest {
        // Пустой `categoryId` бэкенд разобрал бы как битый uuid и ответил
        // `400 TYPE_MISMATCH`.
        server.enqueue(envelope("""{"products":[]}"""))

        repository().catalog(storeId = STORE, categoryId = "  ")

        assertFalse(server.takeRequest().path.orEmpty().contains("categoryId"))
    }

    @Test
    fun `catalog page carries products and knows whether there is more`() = runTest {
        server.enqueue(
            envelope(
                """{"products":[
                     {"id":"p-1","storeId":"$STORE","name":"Oq ko'ylak","brand":"Mahalla",
                      "gender":"FEMALE","basePrice":320000,"salePrice":240000,
                      "ratingAvg":4.5,"ratingCount":12,"isNew":true,"bestseller":true},
                     {"name":"Nomsiz"}],
                   "page":0,"totalPages":3,"totalElements":42}""",
            ),
        )

        val page = (repository().catalog(STORE) as ApiResult.Success).data

        // Товар без id выбрасывается: открыть его нечем, а в списке это
        // дубликат ключа.
        val product = page.items.single()
        assertEquals("p-1", product.id)
        assertEquals(ProductGender.Female, product.gender)
        assertEquals(240_000L, product.priceSum)
        assertTrue(product.hasDiscount)
        assertTrue(product.isNew)
        // Jackson сериализует `boolean isBestseller` и как `bestseller`.
        assertTrue(product.isBestseller)
        assertTrue(page.hasMore)
    }

    @Test
    fun `product variants come out of the colour map`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"p-1","storeId":"$STORE","name":"Oq ko'ylak","description":"Paxta",
                    "material":"Paxta 100%","gender":"UNISEX","basePrice":320000,
                    "variantsByColor":{
                      "Oq":[{"id":"v-1","size":"M","price":240000,"stockQuantity":3},
                            {"id":"v-2","size":"L","price":240000,"stockQuantity":0},
                            {"size":"XL"}],
                      "Qora":[{"id":"v-3","colorName":"Qora","size":"M","price":260000,
                               "available":false}]}}""",
            ),
        )

        val detail = (repository().product("p-1") as ApiResult.Success).data

        assertEquals("/fashion/products/p-1", server.takeRequest().path)
        // Имя цвета берётся из ключа карты: у самого варианта поля может и не
        // быть — сервер уже сказал цвет ключом.
        assertEquals(listOf("Oq", "Qora"), detail.colors)
        // Вариант без id выбрасывается: положить в корзину его нечем.
        assertEquals(listOf("v-1", "v-2", "v-3"), detail.variants.map(ProductVariant::id))
        assertTrue(detail.variant("v-1")!!.isOrderable)
        assertFalse(detail.variant("v-2")!!.isOrderable)
        assertFalse(detail.variant("v-3")!!.isOrderable)
        assertEquals("Paxta 100%", detail.material)
    }

    @Test
    fun `product without id is a failure, not an empty card`() = runTest {
        server.enqueue(envelope("""{"name":"Nomsiz"}"""))

        val result = repository().product("p-1") as ApiResult.Failure

        assertEquals(ApiError.Serialization, result.error)
    }

    @Test
    fun `envelope with success false is a failure with the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"STORE_CLOSED","message":"Do'kon yopiq"}}""",
                ),
        )

        val failure = (repository().catalog(STORE) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("STORE_CLOSED"), failure.error)
        assertEquals("Do'kon yopiq", failure.serverMessage)
    }

    @Test
    fun `unknown product answers with the not found the stand actually sends`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"NOT_FOUND",
                       "message":"Mahsulot topilmadi: p-1"}}""",
                ),
        )

        val failure = (repository().product("p-1") as ApiResult.Failure).failure

        assertEquals(ApiError.NotFound, failure.error)
        assertEquals("Mahsulot topilmadi: p-1", failure.serverMessage)
    }

    private fun repository() = DefaultFashionRepository(api = fashionApi(server))

    private companion object {
        const val STORE = "11111111-1111-1111-1111-111111111111"
    }
}

/** Общий для тестов вертикали Retrofit на MockWebServer. */
internal fun fashionApi(server: MockWebServer): FashionApi = NetworkFactory
    .retrofit(
        server.url("/").toString(),
        NetworkFactory.clientBuilder().build(),
        NetworkFactory.converterFactory(NetworkFactory.json()),
    )
    .create(FashionApi::class.java)

/** Успешный конверт бэкенда: `{success, data}`. */
internal fun envelope(data: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
    .setBody("""{"success":true,"data":$data}""")
