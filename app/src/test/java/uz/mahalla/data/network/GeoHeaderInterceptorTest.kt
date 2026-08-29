package uz.mahalla.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Координаты в заголовках каждого запроса (issue #53).
 *
 * Бэкенд отклоняет запрос без пары `X-Geo-Lat` / `X-Geo-Lng` ещё до
 * маршрутизации — `403 GEO_PERMISSION_REQUIRED`, независимо от разрешения на
 * устройстве. Нечисловое значение он отвергает отдельно
 * (`GEO_INVALID_COORDINATES`), поэтому формат проверяется тоже.
 */
class GeoHeaderInterceptorTest {

    private lateinit var server: MockWebServer

    private val provider = FakeLocationProvider()
    private var now: Long = 1_774_000_000L

    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochSecond(now)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `both coordinates go with the request`() {
        provider.location = DeviceLocation(latitude = 41.3111, longitude = 69.2797)

        val request = call()

        assertEquals("41.311100", request.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
        assertEquals("69.279700", request.getHeader(GeoHeaderInterceptor.HEADER_LONGITUDE))
    }

    @Test
    fun `coordinates are decimal in any locale and never exponential`() {
        // Локаль по умолчанию могла бы дать «41,3111», а Double.toString() —
        // «1.0E-5» для точек у нулевого меридиана. И то, и другое бэкенд
        // считает GEO_INVALID_COORDINATES.
        val defaultLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"))
        try {
            provider.location = DeviceLocation(latitude = 0.00001, longitude = 41.3111)

            val request = call()

            assertEquals("0.000010", request.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
            assertEquals("41.311100", request.getHeader(GeoHeaderInterceptor.HEADER_LONGITUDE))
        } finally {
            java.util.Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `coordinates are read once per ttl, not once per request`() {
        // Интерцептор работает на потоке OkHttp: опрашивать LocationManager и
        // DataStore на каждый запрос прокрутки списка незачем.
        provider.location = DeviceLocation(latitude = 41.0, longitude = 69.0)
        call()

        provider.location = DeviceLocation(latitude = 42.0, longitude = 70.0)
        val cached = call()
        now += GeoHeaderInterceptor.CACHE_TTL_SECONDS
        val refreshed = call()

        assertEquals("источник опрошен один раз за TTL", 2, provider.calls)
        assertEquals("41.000000", cached.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
        assertEquals("42.000000", refreshed.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
    }

    @Test
    fun `a failing location source does not break the request`() {
        // Без координат бэкенд ответит понятным 403; исключение из
        // интерцептора превратилось бы в «сеть недоступна».
        provider.failure = IllegalStateException("DataStore недоступен")

        val request = call()

        assertNull(request.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
    }

    @Test
    fun `the last known coordinates survive a failure`() {
        provider.location = DeviceLocation(latitude = 41.0, longitude = 69.0)
        call()

        now += GeoHeaderInterceptor.CACHE_TTL_SECONDS
        provider.failure = IllegalStateException("разрешение отозвали")
        val request = call()

        assertEquals("41.000000", request.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
    }

    @Test
    fun `an explicit header is not overwritten`() {
        provider.location = DeviceLocation(latitude = 41.0, longitude = 69.0)

        val request = call(
            Request.Builder()
                .url(server.url("/places/nearby"))
                .header(GeoHeaderInterceptor.HEADER_LATITUDE, "1.0")
                .header(GeoHeaderInterceptor.HEADER_LONGITUDE, "2.0"),
        )

        assertEquals("1.0", request.getHeader(GeoHeaderInterceptor.HEADER_LATITUDE))
    }

    /** Клиент один на тест: кэш координат живёт в самом интерцепторе. */
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(GeoHeaderInterceptor(provider, clock)).build()
    }

    private fun call(
        builder: Request.Builder = Request.Builder().url(server.url("/places/nearby")),
    ): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(builder.build()).execute().close()
        return server.takeRequest()
    }

    private class FakeLocationProvider : RequestLocationProvider {
        var location = DeviceLocation(latitude = 0.0, longitude = 0.0)
        var failure: Throwable? = null
        var calls = 0

        override suspend fun current(): DeviceLocation {
            calls++
            failure?.let { throw it }
            return location
        }
    }
}
