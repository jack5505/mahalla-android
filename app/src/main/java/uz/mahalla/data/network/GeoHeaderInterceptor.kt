package uz.mahalla.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Координаты устройства в каждом запросе (issue #53).
 *
 * Бэкенд проверяет их глобальным фильтром, до маршрутизации: запрос без пары
 * заголовков `X-Geo-Lat` / `X-Geo-Lng` получает `403 GEO_PERMISSION_REQUIRED`
 * («Joylashuv ruxsatini yoqing») независимо от того, выдано ли разрешение на
 * устройстве и есть ли `lat`/`lng` в query. Проверено на стенде:
 *
 * ```
 * GET /api/v1/places/nearby?lat=41.3111&lng=69.2797            → 403 GEO_PERMISSION_REQUIRED
 * GET … -H 'X-Geo-Lat: 41.3111'                                → 403 GEO_PERMISSION_REQUIRED
 * GET … -H 'X-Geo-Lat: abc' -H 'X-Geo-Lng: def'                → 403 GEO_INVALID_COORDINATES
 * GET … -H 'X-Geo-Lat: 41.3111' -H 'X-Geo-Lng: 69.2797'        → 200
 * ```
 *
 * Поэтому заголовки ставятся на **оба** клиента и на все запросы: авторизации
 * они не мешают (проверено — `send-otp` с ними отвечает 200), а любой новый
 * эндпоинт иначе снова упёрся бы в 403.
 *
 * Координаты берутся у [RequestLocationProvider] — то есть настоящая позиция,
 * а если разрешения нет, центр выбранного города. Значение кэшируется на
 * [CACHE_TTL_SECONDS]: [Interceptor] выполняется на потоке OkHttp, и опрашивать
 * `LocationManager` с DataStore на каждый запрос выдачи незачем — за время
 * прокрутки списка человек никуда не уедет.
 */
@Singleton
class GeoHeaderInterceptor @Inject constructor(
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
) : Interceptor {

    private data class Cached(val location: DeviceLocation, val atEpochSeconds: Long)

    @Volatile
    private var cached: Cached? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.hasGeoHeaders()) return chain.proceed(request)

        val location = location() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header(HEADER_LATITUDE, format(location.latitude))
                .header(HEADER_LONGITUDE, format(location.longitude))
                .build(),
        )
    }

    /**
     * `runBlocking` здесь уместен по той же причине, что и в [AuthInterceptor]:
     * интерцептор уже работает на пуле OkHttp, а не на Main.
     *
     * Отказ хранилища не должен ронять запрос: без координат он получит
     * понятный 403 от бэкенда, а исключение из интерцептора превратилось бы в
     * «сеть недоступна».
     */
    private fun location(): DeviceLocation? {
        val now = clock.instant().epochSecond
        cached?.let { if (now - it.atEpochSeconds < CACHE_TTL_SECONDS) return it.location }

        val fresh = runBlocking { runCatchingCancellable { locationProvider.current() }.getOrNull() }
            ?: return cached?.location
        cached = Cached(fresh, now)
        return fresh
    }

    private fun Request.hasGeoHeaders(): Boolean =
        header(HEADER_LATITUDE) != null && header(HEADER_LONGITUDE) != null

    /**
     * Точка — только десятичная (`Locale.ROOT`), экспоненциальной записи нет:
     * `Double.toString()` отдал бы `1.0E-5` для координат у нулевого меридиана,
     * и бэкенд ответил бы `GEO_INVALID_COORDINATES`. Шести знаков хватает на
     * точность порядка 0.1 м.
     */
    private fun format(value: Double): String = String.format(Locale.ROOT, COORDINATE_FORMAT, value)

    companion object {
        const val HEADER_LATITUDE = "X-Geo-Lat"
        const val HEADER_LONGITUDE = "X-Geo-Lng"

        private const val COORDINATE_FORMAT = "%.6f"

        /** Как долго переиспользуем однажды прочитанные координаты. */
        const val CACHE_TTL_SECONDS = 60L
    }
}
