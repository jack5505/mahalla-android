package uz.mahalla.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.onboarding.domain.City
import javax.inject.Inject
import javax.inject.Singleton

/** Координаты в том виде, в каком их ждёт бэкенд. */
data class DeviceLocation(val latitude: Double, val longitude: Double)

/**
 * Последние известные координаты устройства или `null`, если разрешения нет
 * и спрашивать его сейчас нечем.
 *
 * За интерфейсом ради тестов: и `LocationManager`, и проверка разрешений —
 * это Android, а логика выбора запасных координат от них не зависит.
 */
interface LocationSource {
    suspend fun lastKnown(): DeviceLocation?
}

/**
 * Координаты для запросов авторизации.
 *
 * Бэкенд (jack5505/mahalla#80) объявил `lat`/`lng` обязательными у
 * `send-otp`, `verify-otp` и `refresh`: без них ответ — 400
 * `VALIDATION_ERROR` с текстом «Joylashuv ruxsatini yoqing». А разрешение на
 * геолокацию приложение просит в конце онбординга (шаг 3.6) — то есть на
 * экране ввода телефона настоящих координат обычно ещё нет.
 *
 * Поэтому источников три, по убыванию точности:
 *  1. последняя известная позиция — когда разрешение уже выдано (повторный
 *     вход, пользователь прошёл онбординг раньше);
 *  2. центр города, выбранного вручную после отказа в разрешении;
 *  3. центр Ташкента.
 *
 * Приблизительные координаты честнее отказа: без них человек не может даже
 * запросить код из SMS. Если бэкенду понадобится именно измеренная позиция,
 * шаг геолокации придётся перенести в начало онбординга — это решение
 * продукта, а не клиента.
 */
interface RequestLocationProvider {
    suspend fun current(): DeviceLocation
}

@Singleton
class DefaultRequestLocationProvider @Inject constructor(
    private val locationSource: LocationSource,
    private val settings: SettingsDataStore,
) : RequestLocationProvider {

    override suspend fun current(): DeviceLocation = locationSource.lastKnown() ?: fallback()

    private suspend fun fallback(): DeviceLocation {
        val city = City.fromId(settings.current().cityId) ?: City.Default
        return DeviceLocation(latitude = city.latitude, longitude = city.longitude)
    }
}

@Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    /**
     * Только последняя известная позиция: запрашивать свежую — это ожидание
     * фикса на экране, где человек набирает номер телефона.
     *
     * Провайдеры перебираются все, потому что доступность у них разная:
     * `gps` молчит в помещении, `network` требует сети, `passive` отдаёт то,
     * что намерил кто-то другой. Берём самое свежее значение.
     */
    // Разрешение проверяется строкой ниже (hasPermission), а отзыв прямо между
    // проверкой и вызовом закрыт runCatchingCancellable: SecurityException
    // здесь — не исключительная ситуация, а «координат нет».
    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): DeviceLocation? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val manager = context.getSystemService<LocationManager>() ?: return@withContext null

        runCatchingCancellable {
            manager.getProviders(/* enabledOnly = */ true)
                .mapNotNull { provider ->
                    // SecurityException возможен и после проверки разрешения:
                    // пользователь может отозвать его между вызовами.
                    runCatchingCancellable { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
                ?.let { DeviceLocation(latitude = it.latitude, longitude = it.longitude) }
        }.getOrNull()
    }

    /** Грубых координат достаточно: бэкенду нужен город, а не подъезд. */
    private fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
