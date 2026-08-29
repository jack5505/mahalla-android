package uz.mahalla.data.network

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.discovery.data.CatalogApi
import java.io.File

/**
 * Запросы уходят на адрес, введённый пользователем (issue #26).
 *
 * Retrofit собирается на `baseUrl` сборки — ровно как в графе, — а запрос
 * должен приехать на MockWebServer: это и есть проверка того, что смена
 * адреса в рантайме работает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackendUrlInterceptorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun `request lands on the entered address`() = runTest {
        val store = store()
        store.save(server.url("/").toString())
        server.enqueue(jsonResponse())

        val result = apiCall { catalogApi(store).place("p-1") }

        assertTrue("ответ не разобрался: $result", result is ApiResult.Success)
        val request = server.takeRequest()
        // Путь baseUrl сборки (/api/v1) срезан: у введённого адреса его нет.
        assertEquals("/places/p-1", request.path)
    }

    @Test
    fun `path of the entered address is kept as a prefix`() = runTest {
        val store = store()
        store.save(server.url("/backend/api/").toString())
        server.enqueue(jsonResponse())

        apiCall { catalogApi(store).place("p-1") }

        assertEquals("/backend/api/places/p-1", server.takeRequest().path)
    }

    @Test
    fun `query parameters survive the rewrite`() = runTest {
        val store = store()
        store.save(server.url("/").toString())
        server.enqueue(jsonResponse("""{"success":true,"data":[]}"""))

        apiCall { catalogApi(store).search(query = "osh", category = "FOOD") }

        val path = server.takeRequest().path.orEmpty()
        assertEquals("/search", path.substringBefore("?"))
        listOf("query=osh", "category=FOOD").forEach {
            assertTrue("в '$path' нет '$it'", path.contains(it))
        }
    }

    private fun catalogApi(store: BackendUrlStore): CatalogApi = NetworkFactory
        .retrofit(
            baseUrl = BUILD_URL,
            client = NetworkFactory.clientBuilder()
                .addInterceptor(BackendUrlInterceptor(store, BUILD_URL))
                .build(),
            converterFactory = NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        .create(CatalogApi::class.java)

    private fun store() = BackendUrlStore(
        SettingsDataStore(
            PreferenceDataStoreFactory.create(
                produceFile = { File(temporaryFolder.root, "backend-url.preferences_pb") },
            ),
        ),
        BUILD_URL,
    )

    private fun jsonResponse(body: String = PLACE_BODY): MockResponse = MockResponse()
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"
        const val PLACE_BODY = """{"success":true,"data":{"id":"p-1","name":"Osh markazi"}}"""
    }
}
