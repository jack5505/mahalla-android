package uz.mahalla.feature.map.data

import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uz.mahalla.feature.map.canvas.MapCoordinates
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * «Моё местоположение» (эпик 4.2).
 *
 * Отдельный интерфейс, а не вызов MapKit из ViewModel: координаты нужны экрану
 * карты и блоку «рядом» на главной, а на JVM MapKit не поднимается — иначе оба
 * экрана стали бы непроверяемыми.
 */
interface UserLocationProvider {

    /**
     * Текущие координаты или `null`, если разрешения нет, геолокация выключена
     * или устройство не успело определиться. Отсутствие координат — норма, а не
     * ошибка: пользователь мог отказать ещё в онбординге (3.6).
     */
    suspend fun currentLocation(): MapCoordinates?
}

/**
 * Реализация поверх `LocationManager` MapKit: своего клиента Google Play
 * Services в проект не тянем — SDK карты уже умеет то же самое, а лишняя
 * зависимость от GMS ломала бы устройства без сервисов Google (в Узбекистане
 * их заметно).
 */
class MapKitLocationProvider(
    private val initializer: MapKitInitializer,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** См. [MapKitInitializer]: весь MapKit живёт на главном потоке. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : UserLocationProvider {

    /**
     * Сильная ссылка на слушателя, пока идёт ожидание: `requestSingleUpdate`
     * принимает `WeakReference` и сам ничего не удерживает — без этого поля
     * слушателя могло бы собрать GC, и координаты не приходили бы никогда.
     */
    private var pendingListener: LocationListener? = null

    override suspend fun currentLocation(): MapCoordinates? {
        // ensureInitialized сам уходит на главный поток и не блокирует чужой.
        if (initializer.ensureInitialized() != MapEngineState.Ready) return null

        return withContext(mainDispatcher) {
            withTimeoutOrNull(timeoutMillis) {
                runCatchingMapKit { awaitSingleUpdate() }.getOrNull()
            }
        }
    }

    private suspend fun awaitSingleUpdate(): MapCoordinates? =
        suspendCancellableCoroutine { continuation ->
            val locationManager = MapKitFactory.getInstance().createLocationManager()

            // Отписываемся ровно той обёрткой, которой подписывались: сравнивает
            // MapKit сам WeakReference или его referent — из API не следует, а
            // одна и та же ссылка верна при любом из двух вариантов.
            lateinit var listenerRef: WeakReference<LocationListener>

            val listener = object : LocationListener {
                override fun onLocationUpdated(location: Location) {
                    locationManager.unsubscribe(listenerRef)
                    pendingListener = null
                    if (continuation.isActive) {
                        continuation.resume(
                            MapCoordinates(
                                latitude = location.position.latitude,
                                longitude = location.position.longitude,
                            ),
                        )
                    }
                }

                override fun onLocationStatusUpdated(status: LocationStatus) {
                    if (status == LocationStatus.NOT_AVAILABLE) {
                        locationManager.unsubscribe(listenerRef)
                        pendingListener = null
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }

            listenerRef = WeakReference(listener)
            pendingListener = listener

            locationManager.requestSingleUpdate(listenerRef)
            continuation.invokeOnCancellation {
                locationManager.unsubscribe(listenerRef)
                pendingListener = null
            }
        }

    private companion object {
        /**
         * Дольше ждать нечего: экран должен показать карту, а не крутилку.
         * Не успели — карта останется на городе по умолчанию, кнопка «моё
         * местоположение» никуда не денется.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
