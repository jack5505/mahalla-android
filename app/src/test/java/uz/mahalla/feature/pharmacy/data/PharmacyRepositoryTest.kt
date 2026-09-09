package uz.mahalla.feature.pharmacy.data

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
import uz.mahalla.feature.pharmacy.domain.ProductStock

/**
 * Витрина аптеки (issue #100) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04 живыми curl'ами: ручка **анонимна**
 * (`200` без токена), требует гео-заголовков (`403` без них — их ставит
 * `GeoHeaderInterceptor`, issue #53), а `placeId` обязан быть uuid
 * (`400 TYPE_MISMATCH` на числовой id). Пагинация и серверный поиск есть —
 * `?query=aspirin&page=2&size=5` возвращает `page: 2, size: 5`.
 */
class PharmacyRepositoryTest {

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
    fun `products are requested by place and parsed out of the envelope`() = runTest {
        server.enqueue(
            page(
                """[{"id":"p-1","name":"Paratsetamol","manufacturer":"Uzpharm",
                   "dosageForm":"tabletka","strength":"500 mg","price":12000,
                   "stockQuantity":4,"isAvailable":true,"requiresPrescription":false}]""",
            ),
        )

        val result = (repository().products(PLACE) as ApiResult.Success).data

        assertEquals(
            "/pharmacy/places/$PLACE/products?page=0&size=20",
            server.takeRequest().path,
        )
        val product = result.items.single()
        assertEquals("p-1", product.id)
        assertEquals("Paratsetamol", product.name)
        assertEquals("Uzpharm", product.manufacturer)
        assertEquals("tabletka", product.dosageForm)
        assertEquals("500 mg", product.strength)
        assertEquals(12_000L, product.priceSum)
        assertEquals(4, product.stockQuantity)
        assertEquals(ProductStock.InStock, product.stock)
        assertFalse(product.requiresPrescription)
    }

    @Test
    fun `a search query is sent to the server, an empty one is not`() = runTest {
        // Пагинация у ручки есть, поэтому фильтровать приехавшее нельзя:
        // совпадение с третьей страницы было бы невидимо.
        server.enqueue(page("[]"))
        repository().products(PLACE, query = "  aspirin  ")

        assertEquals(
            "/pharmacy/places/$PLACE/products?query=aspirin&page=0&size=20",
            server.takeRequest().path,
        )

        server.enqueue(page("[]"))
        repository().products(PLACE, query = "   ")

        // Пустой параметр необязателен — и просить сервер искать пустую строку
        // незачем.
        assertEquals("/pharmacy/places/$PLACE/products?page=0&size=20", server.takeRequest().path)
    }

    @Test
    fun `page and size travel to the server as they are`() = runTest {
        server.enqueue(page("[]", page = 2, size = 5))

        repository().products(PLACE, page = 2, size = 5)

        assertEquals(
            "/pharmacy/places/$PLACE/products?page=2&size=5",
            server.takeRequest().path,
        )
    }

    @Test
    fun `both spellings of the availability flag are understood`() = runTest {
        // Jackson сериализует `boolean isAvailable` то так, то так; ошибка
        // здесь показала бы «нет в наличии» у всей аптеки.
        server.enqueue(
            page(
                """[{"id":"p-1","name":"A","available":false},
                   {"id":"p-2","name":"B","isAvailable":false},
                   {"id":"p-3","name":"C","available":true}]""",
            ),
        )

        val items = (repository().products(PLACE) as ApiResult.Success).data.items

        assertEquals(
            listOf(ProductStock.OutOfStock, ProductStock.OutOfStock, ProductStock.InStock),
            items.map { it.stock },
        )
    }

    @Test
    fun `both spellings of the prescription flag are understood`() = runTest {
        server.enqueue(
            page(
                """[{"id":"p-1","name":"A","requiresPrescription":true},
                   {"id":"p-2","name":"B","prescriptionRequired":true},
                   {"id":"p-3","name":"C"}]""",
            ),
        )

        val items = (repository().products(PLACE) as ApiResult.Success).data.items

        assertEquals(listOf(true, true, false), items.map { it.requiresPrescription })
    }

    @Test
    fun `a product that cannot be shown is dropped, the rest of the page survives`() = runTest {
        // Без `id` — дубликат ключа в LazyColumn; без имени — строка, у
        // которой нечего прочитать. Всё остальное витрину не роняет.
        server.enqueue(
            page(
                """[{"name":"Nomsiz"},
                   {"id":"","name":"Bo'sh id"},
                   {"id":"p-2","name":"   "},
                   {"id":"p-3","name":"Askorbin"}]""",
            ),
        )

        val items = (repository().products(PLACE) as ApiResult.Success).data.items

        assertEquals(listOf("p-3"), items.map { it.id })
    }

    @Test
    fun `garbage in numbers does not hide the product`() = runTest {
        server.enqueue(
            page("""[{"id":"p-1","name":"Paratsetamol","price":-1,"stockQuantity":-3}]"""),
        )

        val product = (repository().products(PLACE) as ApiResult.Success).data.items.single()

        assertNull(product.priceSum)
        assertNull(product.stockQuantity)
        // Ни цены, ни остатка, ни флага — обещать наличие не на чем.
        assertEquals(ProductStock.Unknown, product.stock)
    }

    @Test
    fun `a free item is not confused with a priceless one`() = runTest {
        server.enqueue(page("""[{"id":"p-1","name":"Bepul","price":0}]"""))

        val product = (repository().products(PLACE) as ApiResult.Success).data.items.single()

        assertEquals(0L, product.priceSum)
    }

    @Test
    fun `hasMore follows last, then page and totalPages, then silence`() = runTest {
        val repository = repository()

        server.enqueue(page("""[{"id":"p-1","name":"A"}]""", last = false))
        assertTrue((repository.products(PLACE) as ApiResult.Success).data.hasMore)

        server.enqueue(page("""[{"id":"p-1","name":"A"}]""", last = true))
        assertFalse((repository.products(PLACE) as ApiResult.Success).data.hasMore)

        server.enqueue(
            envelope(
                """{"content":[{"id":"p-1","name":"A"}],"page":0,"totalPages":3}""",
            ),
        )
        assertTrue((repository.products(PLACE) as ApiResult.Success).data.hasMore)

        // Сервер о страницах промолчал: лучше не показать хвост, чем крутить
        // одну и ту же страницу в цикле.
        server.enqueue(envelope("""{"content":[{"id":"p-1","name":"A"}]}"""))
        assertFalse((repository.products(PLACE) as ApiResult.Success).data.hasMore)
    }

    @Test
    fun `an empty showcase is a normal answer, not a failure`() = runTest {
        // Ровно то, что отвечает стенд: каталог пуст (issue #53).
        server.enqueue(page("[]"))

        val result = (repository().products(PLACE) as ApiResult.Success).data

        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun `an empty place id never reaches the network`() = runTest {
        val failure = (repository().products("") as ApiResult.Failure).failure

        assertEquals(
            ApiError.Business(PharmacyRepository.INVALID_REQUEST_CODE),
            failure.error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a non-uuid place id is a server failure with its own text`() = runTest {
        // Ровно то, что отвечает стенд на числовой `placeId`.
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"TYPE_MISMATCH",
                       "message":"Noto'g'ri parametr turi: placeId"}}""",
                ),
        )

        val failure = (repository().products("1") as ApiResult.Failure).failure

        assertEquals("Noto'g'ri parametr turi: placeId", failure.serverMessage)
    }

    @Test
    fun `success false is a failure, not an empty showcase`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SERVICE_UNAVAILABLE",
                       "message":"Vaqtincha ishlamayapti"}}""",
                ),
        )

        val failure = (repository().products(PLACE) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("SERVICE_UNAVAILABLE"), failure.error)
        assertEquals("Vaqtincha ishlamayapti", failure.serverMessage)
    }

    @Test
    fun `the 403 the stand returns without geo headers keeps its own text`() = runTest {
        // Заголовки ставит GeoHeaderInterceptor (issue #53), которого в этом
        // тесте нет: важно, что причина доезжает до экрана словами сервера.
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",
                       "message":"Joylashuv ruxsatini yoqing"}}""",
                ),
        )

        val failure = (repository().products(PLACE) as ApiResult.Failure).failure

        assertEquals(ApiError.Forbidden, failure.error)
        assertEquals("Joylashuv ruxsatini yoqing", failure.serverMessage)
    }

    private fun repository() = DefaultPharmacyRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(PharmacyApi::class.java),
    )

    private fun page(
        content: String,
        page: Int = 0,
        size: Int = 20,
        last: Boolean = true,
    ): MockResponse = envelope(
        """{"content":$content,"page":$page,"size":$size,
           "totalElements":0,"totalPages":1,"first":true,"last":$last}""",
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        const val PLACE = "11111111-1111-1111-1111-111111111111"
    }
}
