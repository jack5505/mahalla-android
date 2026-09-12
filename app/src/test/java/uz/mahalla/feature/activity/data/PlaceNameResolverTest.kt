package uz.mahalla.feature.activity.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.data.CatalogApi

/**
 * `PlaceNameResolver` (issue #182, снимает клиентскую часть #150) на
 * настоящем сетевом стеке ([NetworkFactory] + [MockWebServer]): подмена
 * `CatalogApi` фейком не поймала бы форму query-параметра `ids` — Retrofit
 * обязан повторить его на каждый id (`ids=a&ids=b`), а не склеить запятой.
 */
class PlaceNameResolverTest {

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
    fun `an empty list of ids makes no request`() = runTest {
        val resolved = resolver().resolve(emptyList())

        assertTrue(resolved.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a batch of ids is resolved in one request, deduplicated`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"p-1","name":"Osh Markazi","category":"FOOD","logoUrl":"https://x/1.png"},
                   {"id":"p-2","name":"Zara","category":"CLOTHING"}]""",
            ),
        )

        val resolved = resolver().resolve(listOf("p-1", "p-2", "p-1"))

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("ids=p-1&ids=p-2", request.requestUrl?.query)
        assertEquals("Osh Markazi", resolved.getValue("p-1").name)
        assertEquals("https://x/1.png", resolved.getValue("p-1").logoUrl)
        assertEquals("Zara", resolved.getValue("p-2").name)
    }

    @Test
    fun `more ids than the batch size are split into several requests`() = runTest {
        // Лимита на число `ids` в схеме нет — резать на пачки по 50 обязан
        // клиент, иначе длинная строка запроса упрётся в ограничение сервера.
        repeat(3) { server.enqueue(envelope("[]")) }

        resolver().resolve((1..120).map { "p-$it" })

        assertEquals(3, server.requestCount)
        val sizes = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest())
            .map { it.requestUrl?.queryParameterValues("ids")?.size }
        assertEquals(listOf(50, 50, 20), sizes)
    }

    @Test
    fun `an id missing from the response stays unresolved`() = runTest {
        server.enqueue(envelope("""[{"id":"p-1","name":"Osh Markazi"}]"""))

        val resolved = resolver().resolve(listOf("p-1", "p-404"))

        assertEquals(setOf("p-1"), resolved.keys)
    }

    @Test
    fun `a resolved id is cached and not asked again`() = runTest {
        server.enqueue(envelope("""[{"id":"p-1","name":"Osh Markazi"}]"""))
        val instance = resolver()

        instance.resolve(listOf("p-1"))
        val second = instance.resolve(listOf("p-1"))

        assertEquals(1, server.requestCount)
        assertEquals("Osh Markazi", second.getValue("p-1").name)
    }

    @Test
    fun `a failed batch does not throw and leaves its ids unresolved`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val resolved = resolver().resolve(listOf("p-1"))

        assertTrue(resolved.isEmpty())
    }

    private fun resolver(): DefaultPlaceNameResolver {
        val retrofit = NetworkFactory.retrofit(
            server.url("/").toString(),
            NetworkFactory.clientBuilder().build(),
            NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        return DefaultPlaceNameResolver(retrofit.create(CatalogApi::class.java))
    }

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
