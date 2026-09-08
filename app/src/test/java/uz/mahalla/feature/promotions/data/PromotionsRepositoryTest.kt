package uz.mahalla.feature.promotions.data

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
import uz.mahalla.feature.promotions.domain.PromoType
import uz.mahalla.feature.promotions.domain.Promotion
import java.time.Instant

/**
 * Акции (issue #104) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET promotions/platform?page&size` → страница
 * `Promotion` в конверте, `GET promotions/places/{placeId}` → список в
 * конверте. Обе ручки анонимны (`200` без токена).
 */
class PromotionsRepositoryTest {

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
    fun `platform promotions are requested by page and parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"promo-1","title":"20% chegirma",
                   "description":"Faqat ish kunlari","promoType":"PERCENT_OFF",
                   "placeId":"p-1","discountPercent":20,"minOrderAmount":50000,
                   "promoCode":"OSH20","startedAt":"2026-09-01T09:00:00",
                   "endedAt":"2026-09-30T18:00:00","isActive":true,
                   "isPlatformWide":true,"valid":true}],"page":0,"last":true}""",
            ),
        )

        val page = (repository().platformPromotions() as ApiResult.Success).data

        assertEquals("/promotions/platform?page=0&size=10", server.takeRequest().path)
        val promotion = page.items.single()
        assertEquals("promo-1", promotion.id)
        assertEquals("20% chegirma", promotion.title)
        assertEquals("Faqat ish kunlari", promotion.description)
        assertEquals(PromoType.PercentOff, promotion.type)
        assertEquals("p-1", promotion.placeId)
        assertEquals(20, promotion.discountPercent)
        assertEquals(50_000L, promotion.minOrderAmount)
        assertEquals("OSH20", promotion.promoCode)
        // Jackson на бэкенде отдаёт дату без зоны — иначе срок пуст у всех.
        assertEquals(Instant.parse("2026-09-01T09:00:00Z"), promotion.startsAt)
        assertEquals(Instant.parse("2026-09-30T18:00:00Z"), promotion.endsAt)
        assertTrue(promotion.isPlatformWide)
        assertFalse(page.hasMore)
    }

    @Test
    fun `a page is asked by number and size`() = runTest {
        server.enqueue(envelope("""{"content":[],"page":2,"totalPages":5}"""))

        val page = (repository().platformPromotions(page = 2, size = 20) as ApiResult.Success).data

        assertEquals("/promotions/platform?page=2&size=20", server.takeRequest().path)
        // `last` не приехал — «есть ли ещё» считается по номеру страницы.
        assertTrue(page.hasMore)
    }

    @Test
    fun `silence about paging stops the load more loop`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"promo-1","title":"Aksiya"}]}"""))

        val page = (repository().platformPromotions() as ApiResult.Success).data

        assertFalse(page.hasMore)
    }

    @Test
    fun `an empty answer is an empty list, not a failure`() = runTest {
        // Ровно так отвечает стенд: реестр акций там пока пуст.
        server.enqueue(
            envelope("""{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"first":true,"last":true}"""),
        )

        val page = (repository().platformPromotions() as ApiResult.Success).data

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `flags are accepted under both names the backend may use`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"a","title":"A","active":false,"platformWide":true},
                   {"id":"b","title":"B","isActive":false,"isPlatformWide":true}],"last":true}""",
            ),
        )

        val items = (repository().platformPromotions() as ApiResult.Success).data.items

        // Ошибка здесь спрятала бы все акции разом.
        assertEquals(listOf(false, false), items.map(Promotion::isActive))
        assertEquals(listOf(true, true), items.map(Promotion::isPlatformWide))
    }

    @Test
    fun `an entry without an id is dropped instead of breaking the list`() = runTest {
        server.enqueue(
            envelope("""{"content":[{"title":"Aksiya"},{"id":"promo-2","title":"Aksiya"}],"last":true}"""),
        )

        val items = (repository().platformPromotions() as ApiResult.Success).data.items

        assertEquals(listOf("promo-2"), items.map(Promotion::id))
    }

    @Test
    fun `an entry without any text is dropped, a description becomes the title`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"a"},{"id":"b","title":" "},
                   {"id":"c","description":"Bepul yetkazib berish"}],"last":true}""",
            ),
        )

        val items = (repository().platformPromotions() as ApiResult.Success).data.items

        // Пустая плашка «акция» читается как поломка экрана.
        assertEquals(listOf("c"), items.map(Promotion::id))
        assertEquals("Bepul yetkazib berish", items.single().title)
        // Второй раз тот же текст не показываем.
        assertNull(items.single().description)
    }

    @Test
    fun `a nonsense discount is dropped but the promotion stays`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"promo-1","title":"Aksiya","promoType":"MEGA",
                   "discountPercent":0,"discountAmount":0,"minOrderAmount":-5,
                   "promoCode":" ","startedAt":"not-a-date"}],"last":true}""",
            ),
        )

        val promotion = (repository().platformPromotions() as ApiResult.Success).data.items.single()

        assertEquals(PromoType.Unknown, promotion.type)
        assertNull(promotion.discountPercent)
        assertNull(promotion.discountAmount)
        assertNull(promotion.minOrderAmount)
        assertNull(promotion.promoCode)
        assertNull(promotion.startsAt)
        // Скидку заведения из-за мусора в соседнем поле не прячем.
        assertEquals("promo-1", promotion.id)
    }

    @Test
    fun `place promotions come as a bare list`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"promo-1","title":"Bepul yetkazib berish",
                   "promoType":"FREE_DELIVERY","placeId":"p-1"}]""",
            ),
        )

        val promotions = (repository().placePromotions("p-1") as ApiResult.Success).data

        assertEquals("/promotions/places/p-1", server.takeRequest().path)
        assertEquals(PromoType.FreeDelivery, promotions.single().type)
        assertEquals("p-1", promotions.single().placeId)
    }

    @Test
    fun `a place without promotions is an empty list`() = runTest {
        // Так отвечает стенд на любой placeId: каталог там пуст (issue #53).
        server.enqueue(envelope("[]"))

        assertTrue((repository().placePromotions("p-1") as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun `success false is a failure, not an empty block`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PROMOTIONS_UNAVAILABLE",
                       "message":"Aksiyalar vaqtincha ishlamayapti"}}""",
                ),
        )

        val failure = (repository().platformPromotions() as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PROMOTIONS_UNAVAILABLE"), failure.error)
        assertEquals("Aksiyalar vaqtincha ishlamayapti", failure.serverMessage)
    }

    @Test
    fun `missing geo headers are reported as the server explained them`() = runTest {
        // Фактический ответ стенда на запрос без X-Geo-Lat/X-Geo-Lng.
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",
                       "message":"Joylashuv ruxsatini yoqing"}}""",
                ),
        )

        val failure = (repository().placePromotions("p-1") as ApiResult.Failure).failure

        assertEquals(ApiError.Forbidden, failure.error)
        assertEquals("Joylashuv ruxsatini yoqing", failure.serverMessage)
    }

    @Test
    fun `a wrong place id is reported as the backend states it`() = runTest {
        // `placeId` — uuid: `promotions/places/1` отвечает 400 TYPE_MISMATCH.
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"TYPE_MISMATCH",
                       "message":"Noto'g'ri parametr turi: placeId"}}""",
                ),
        )

        val failure = (repository().placePromotions("1") as ApiResult.Failure).failure

        assertEquals("TYPE_MISMATCH", failure.server?.code)
        assertEquals("Noto'g'ri parametr turi: placeId", failure.serverMessage)
    }

    private fun repository() = DefaultPromotionsRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(PromotionsApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
