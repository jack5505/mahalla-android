package uz.mahalla.feature.role.data

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
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole

/**
 * Сотрудники заведения (issue #189) на настоящем сетевом стеке ([NetworkFactory]
 * + [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда (`/v3/api-docs`, 2026-09-11): `PlaceStaffResponse`,
 * `AddRequest`, `PlaceStaffChangeRoleRequest` в схеме встречаются по одному
 * разу — коллизии springdoc здесь нет. Роль — закрытое перечисление
 * `STAFF`/`MANAGER`/`OWNER`, то же самое, что и у `Mine.role`.
 */
class PlaceStaffRepositoryTest {

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
    fun `the list is parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","placeId":"p-1","userId":"u-1","role":"MANAGER",
                   "geoExempt":true,"createdAt":"2026-09-10T10:00:00Z"}]""",
            ),
        )

        val staff = (repository().list("p-1") as ApiResult.Success).data

        assertEquals("/places/p-1/staff", server.takeRequest().path)
        val member = staff.single()
        assertEquals("u-1", member.userId)
        assertEquals(PlaceStaffRole.Manager, member.role)
        assertTrue(member.geoExempt)
    }

    @Test
    fun `an entry without a userId is dropped instead of breaking the list`() = runTest {
        server.enqueue(envelope("""[{"id":"s-1","role":"STAFF"},{"id":"s-2","userId":"u-2"}]"""))

        val staff = (repository().list("p-1") as ApiResult.Success).data

        assertEquals(listOf("u-2"), staff.map(PlaceStaffMember::userId))
    }

    @Test
    fun `an unknown role does not hide the entry`() = runTest {
        server.enqueue(envelope("""[{"userId":"u-1","role":"CASHIER"}]"""))

        val member = (repository().list("p-1") as ApiResult.Success).data.single()

        assertEquals(PlaceStaffRole.Unknown, member.role)
    }

    @Test
    fun `add sends the role's api value to the place path`() = runTest {
        server.enqueue(envelope("""{"userId":"u-1","role":"STAFF"}"""))

        repository().add("p-1", "u-1", PlaceStaffRole.Staff)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/places/p-1/staff", request.path)
        assertEquals("""{"userId":"u-1","role":"STAFF"}""", request.body.readUtf8())
    }

    @Test
    fun `a silent add response is filled in from what was sent`() = runTest {
        // Все поля ответа необязательны по схеме — сервер мог промолчать.
        server.enqueue(envelope("{}"))

        val member = (
            repository().add("p-1", "u-1", PlaceStaffRole.Manager) as ApiResult.Success
            ).data

        assertEquals("u-1", member.userId)
        assertEquals(PlaceStaffRole.Manager, member.role)
    }

    @Test
    fun `change role targets the staff user path`() = runTest {
        server.enqueue(envelope("""{"userId":"u-1","role":"OWNER"}"""))

        repository().changeRole("p-1", "u-1", PlaceStaffRole.Owner)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/places/p-1/staff/u-1", request.path)
        assertEquals("""{"role":"OWNER"}""", request.body.readUtf8())
    }

    @Test
    fun `remove targets the staff user path and ignores an empty payload`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().remove("p-1", "u-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/places/p-1/staff/u-1", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `a refusal is reported with the text of the backend`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_FORBIDDEN",
                       "message":"Bu muassasa sizga tegishli emas"}}""",
                ),
        )

        val failure = (repository().remove("p-1", "u-1") as ApiResult.Failure).failure

        assertEquals("PLACE_FORBIDDEN", failure.server?.code)
        assertEquals("Bu muassasa sizga tegishli emas", failure.serverMessage)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().list("p-1") as ApiResult.Failure).error,
        )
    }

    private fun repository() = DefaultPlaceStaffRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(PlaceStaffApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
