package uz.mahalla.feature.social.data

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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.place
import java.time.Instant

/**
 * Контроллер `social` (issue #75) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET places/{id}/status` → `{liked, saved,
 * totalLikes}`, `POST like` → `{liked, totalLikes}`, `POST save` → голый
 * `Boolean`, комментарии — страницами, `GET saved-places` — **только** UUID'ы.
 */
class SocialRepositoryTest {

    private lateinit var server: MockWebServer
    private val catalog = FakeCatalogRepository()
    private val profileStore = FakeUserProfileStore(UserProfile(id = "u-1"))

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
    fun `status feeds both buttons from one answer`() = runTest {
        server.enqueue(envelope("""{"liked":true,"saved":true,"totalLikes":42}"""))

        val status = (repository().status(PLACE_ID) as ApiResult.Success).data

        assertEquals("/places/$PLACE_ID/status", server.takeRequest().path)
        assertTrue(status.liked)
        assertTrue(status.saved)
        assertEquals(42L, status.likes)
    }

    @Test
    fun `like is a toggle and the answer carries the new counter`() = runTest {
        server.enqueue(envelope("""{"liked":true,"totalLikes":43}"""))

        val result = (repository().toggleLike(PLACE_ID) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/places/$PLACE_ID/like", request.path)
        assertTrue(result.liked)
        assertEquals(43L, result.likes)
    }

    @Test
    fun `a like answer without a counter does not pretend it is zero`() = runTest {
        server.enqueue(envelope("""{"liked":false}"""))

        val result = (repository().toggleLike(PLACE_ID) as ApiResult.Success).data

        assertFalse(result.liked)
        assertNull(result.likes)
    }

    @Test
    fun `save returns the new state in the envelope`() = runTest {
        server.enqueue(envelope("false"))

        val saved = (repository().toggleSave(PLACE_ID) as ApiResult.Success).data

        assertEquals("/places/$PLACE_ID/save", server.takeRequest().path)
        assertFalse(saved)
    }

    @Test
    fun `comments arrive as a page and mine are marked as mine`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                     {"id":"c-1","userId":"u-1","text":"Zo'r","createdAt":"2026-08-30T10:15:30"},
                     {"id":"c-2","userId":"u-2","text":"Yaxshi"}
                   ],"page":0,"totalPages":3,"last":false}""",
            ),
        )

        val page = (repository().comments(PLACE_ID) as ApiResult.Success).data

        assertEquals("/places/$PLACE_ID/comments?page=0&size=20", server.takeRequest().path)
        assertEquals(listOf("c-1", "c-2"), page.items.map(PlaceComment::id))
        // Jackson на бэкенде отдаёт `LocalDateTime` без зоны — иначе дата
        // была бы пуста у всех.
        assertEquals(Instant.parse("2026-08-30T10:15:30Z"), page.items.first().createdAt)
        assertTrue(page.items.first().isMine)
        assertFalse(page.items.last().isMine)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a comment without an id is dropped instead of breaking the list`() = runTest {
        // В `LazyColumn` это дубликат ключа, а удалить такую запись всё равно
        // нечем.
        server.enqueue(envelope("""{"content":[{"text":"Bo'sh"},{"id":"c-3","text":"Ok"}]}"""))

        val page = (repository().comments(PLACE_ID) as ApiResult.Success).data

        assertEquals(listOf("c-3"), page.items.map(PlaceComment::id))
        assertFalse(page.hasMore)
    }

    @Test
    fun `a new comment is sent trimmed under the text key`() = runTest {
        server.enqueue(envelope("""{"id":"c-9","userId":"u-1","text":"Zo'r joy"}"""))

        val comment = (repository().addComment(PLACE_ID, "  Zo'r joy \n") as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"text":"Zo'r joy"}""", request.body.readUtf8())
        assertEquals("c-9", comment.id)
        assertTrue(comment.isMine)
    }

    @Test
    fun `an accepted comment stays mine even without a user id in the answer`() = runTest {
        server.enqueue(envelope("""{"id":"c-10","text":"Zo'r"}"""))

        val comment = (repository().addComment(PLACE_ID, "Zo'r") as ApiResult.Success).data

        assertTrue(comment.isMine)
    }

    @Test
    fun `deleting a comment reads the envelope without a payload`() = runTest {
        // `ApiResponseVoid`: `data` пуст и при успехе — payload() превратил бы
        // штатный ответ в ошибку.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().deleteComment("c-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/comments/c-1", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `saved places are collected from ids one card at a time`() = runTest {
        // `GET saved-places` отдаёт только UUID'ы — карточек в контракте нет
        // вовсе, и список собирается N+1 запросом.
        server.enqueue(envelope("""{"content":["p-1","p-2"],"page":0,"totalPages":2,"last":false}"""))
        catalog.cards["p-1"] = ApiResult.Success(place("p-1"))
        catalog.cards["p-2"] = ApiResult.Success(place("p-2"))

        val page = (repository().savedPlaces() as ApiResult.Success).data

        assertEquals("/saved-places?page=0&size=20", server.takeRequest().path)
        // Порядок задаёт сервер: недавно сохранённое должно остаться сверху.
        assertEquals(listOf("p-1", "p-2"), page.items.map(Place::id))
        assertEquals(listOf("p-1", "p-2"), catalog.requestedCards.sorted())
        assertTrue(page.hasMore)
    }

    @Test
    fun `a card that did not arrive drops out of the list`() = runTest {
        server.enqueue(envelope("""{"content":["p-1","p-2"],"last":true}"""))
        catalog.cards["p-1"] = ApiResult.Success(place("p-1"))
        catalog.cards["p-2"] = ApiResult.Failure(ApiError.NotFound)

        val page = (repository().savedPlaces() as ApiResult.Success).data

        assertEquals(listOf("p-1"), page.items.map(Place::id))
        assertFalse(page.hasMore)
    }

    @Test
    fun `if no card arrived at all it is a failure, not an empty favourites list`() = runTest {
        server.enqueue(envelope("""{"content":["p-1"],"last":true}"""))
        catalog.cards["p-1"] = ApiResult.Failure(ApiError.NoConnection)

        val result = repository().savedPlaces()

        assertEquals(ApiError.NoConnection, (result as ApiResult.Failure).error)
    }

    @Test
    fun `an empty favourites page does not go for cards`() = runTest {
        server.enqueue(envelope("""{"content":[],"last":true}"""))

        val page = (repository().savedPlaces() as ApiResult.Success).data

        assertTrue(page.items.isEmpty())
        assertTrue(catalog.requestedCards.isEmpty())
    }

    @Test
    fun `duplicate ids are collapsed before the cards are requested`() = runTest {
        // Один и тот же id дважды — это дубликат ключа в `LazyColumn` и
        // лишний запрос.
        server.enqueue(envelope("""{"content":["p-1","p-1",""],"last":true}"""))
        catalog.cards["p-1"] = ApiResult.Success(place("p-1"))

        val page = (repository().savedPlaces() as ApiResult.Success).data

        assertEquals(listOf("p-1"), page.items.map(Place::id))
        assertEquals(1, catalog.requestedCards.size)
    }

    @Test
    fun `success false is a failure, not an empty answer`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_NOT_FOUND",
                       "message":"Joy topilmadi"}}""",
                ),
        )

        val failure = (repository().status(PLACE_ID) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PLACE_NOT_FOUND"), failure.error)
        assertEquals("Joy topilmadi", failure.serverMessage)
    }

    @Test
    fun `an expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().status(PLACE_ID) as ApiResult.Failure).error,
        )
    }

    private fun repository() = DefaultSocialRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(SocialApi::class.java),
        catalogRepository = catalog,
        profileStore = profileStore,
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        const val PLACE_ID = "p-1"
    }
}
