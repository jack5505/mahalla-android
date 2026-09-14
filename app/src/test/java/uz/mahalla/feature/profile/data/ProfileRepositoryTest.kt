package uz.mahalla.feature.profile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.testutil.FakeUserProfileStore

/**
 * Профиль на сервере (issue #170) на настоящем сетевом стеке ([NetworkFactory]
 * + [MockWebServer]): подмена Retrofit фейком не поймала бы ни путь запроса,
 * ни несовпадение схемы JSON.
 *
 * Контракт снят чтением `/v3/api-docs` 2026-09-10 (issue #237),
 * `docs/API-CONTRACT.md`: `GET`/`PUT users/me`, `PUT` — PATCH по смыслу.
 */
class ProfileRepositoryTest {

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
    fun `refresh reads the profile and replaces the local copy`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"u-1","phone":"+998901234567","fullName":"Alisher Usmonov",
                   "avatarUrl":"https://cdn.mahalla.uz/a.png","role":"USER",
                   "verificationStatus":"SMS_VERIFIED","accountStatus":"ACTIVE"}""",
            ),
        )
        val store = FakeUserProfileStore(UserProfile(fullName = "Устаревшее имя"))

        val result = repository(store).refresh()

        assertTrue(result is ApiResult.Success)
        assertEquals("/users/me", server.takeRequest().path)
        assertEquals(
            UserProfile(
                id = "u-1",
                phone = "+998901234567",
                fullName = "Alisher Usmonov",
                avatarUrl = "https://cdn.mahalla.uz/a.png",
                serverRole = "USER",
                verificationStatus = "SMS_VERIFIED",
                accountStatus = "ACTIVE",
            ),
            store.current(),
        )
    }

    @Test
    fun `refresh sends the pending anketa name via PUT instead of GET`() = runTest {
        server.enqueue(
            envelope("""{"id":"u-1","fullName":"Jahongir","avatarUrl":null}"""),
        )
        val store = FakeUserProfileStore(
            UserProfile(fullName = "Jahongir", fullNamePendingSync = true),
        )

        val result = repository(store).refresh()

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(
            "Jahongir",
            NetworkFactory.json().parseToJsonElement(request.body.readUtf8())
                .jsonObject["fullName"]?.jsonPrimitive?.content,
        )
        // Сервер подтвердил имя — ждать больше нечего.
        assertTrue(!store.current().fullNamePendingSync)
    }

    @Test
    fun `failed pending sync keeps the flag for the next profile open`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val known = UserProfile(fullName = "Jahongir", fullNamePendingSync = true)
        val store = FakeUserProfileStore(known)

        val result = repository(store).refresh()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        // Не доехало — повторится при следующем открытии профиля, имя не теряется.
        assertEquals(known, store.current())
    }

    @Test
    fun `failed refresh does not touch the stored profile`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val known = UserProfile(phone = "+998901234567", fullName = "Alisher Usmonov")
        val store = FakeUserProfileStore(known)

        val result = repository(store).refresh()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        // Профиль, который принёс вход, остаётся на месте — не пустеет.
        assertEquals(known, store.current())
    }

    @Test
    fun `update sends only the changed field`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"u-1","phone":"+998901234567","fullName":"Jahongir Sabirov",
                   "avatarUrl":null}""",
            ),
        )
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))

        val result = repository(store).updateProfile(fullName = "Jahongir Sabirov")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/users/me", request.path)
        assertEquals("PUT", request.method)
        val body = NetworkFactory.json().parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Jahongir Sabirov", body["fullName"]?.jsonPrimitive?.content)
        // Аватар не редактировали — поля в теле нет вовсе, не `null` явно.
        assertNull(body["avatarUrl"])
        assertEquals("Jahongir Sabirov", store.current().fullName)
    }

    @Test
    fun `empty avatar url clears the photo`() = runTest {
        server.enqueue(envelope("""{"id":"u-1","fullName":"Alisher Usmonov","avatarUrl":null}"""))
        val store = FakeUserProfileStore(
            UserProfile(fullName = "Alisher Usmonov", avatarUrl = "https://cdn.mahalla.uz/old.jpg"),
        )

        repository(store).updateProfile(avatarUrl = "")

        val body = NetworkFactory.json()
            .parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject
        assertEquals("", body["avatarUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refused update reports the server code and keeps the old value`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"VALIDATION_ERROR",
                       "message":"Ism juda uzun"}}""",
                ),
        )
        val store = FakeUserProfileStore(UserProfile(fullName = "Alisher Usmonov"))

        val failure = (repository(store).updateProfile(fullName = "x".repeat(300)) as
            ApiResult.Failure).failure

        assertEquals(ApiError.Business("VALIDATION_ERROR"), failure.error)
        assertEquals("Ism juda uzun", failure.serverMessage)
        assertEquals("Alisher Usmonov", store.current().fullName)
    }

    private fun repository(store: FakeUserProfileStore) = DefaultProfileRepository(
        profileApi = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(ProfileApi::class.java),
        userProfileStore = store,
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
