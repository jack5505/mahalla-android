package uz.mahalla.data.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.testutil.FakeLocationSource
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Координаты для запросов авторизации (issue #42).
 *
 * Бэкенд объявил `lat`/`lng` обязательными, а разрешение на геолокацию
 * онбординг просит только на последнем шаге — значит на экране телефона
 * координат обычно нет, и запрос всё равно обязан уйти.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RequestLocationProviderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "location.preferences_pb") },
    )

    @Test
    fun `measured position wins over any fallback`() = runTest {
        val measured = DeviceLocation(latitude = 40.10, longitude = 65.20)

        val location = provider(FakeLocationSource(measured), cityId = City.SAMARKAND.id).current()

        assertEquals(measured, location)
    }

    @Test
    fun `without a permission the chosen city is used`() = runTest {
        val location = provider(FakeLocationSource(), cityId = City.SAMARKAND.id).current()

        assertEquals(
            DeviceLocation(City.SAMARKAND.latitude, City.SAMARKAND.longitude),
            location,
        )
    }

    @Test
    fun `without a city the capital is used`() = runTest {
        // Первый запуск: города ещё не выбирали, геолокацию ещё не спрашивали.
        val location = provider(FakeLocationSource(), cityId = null).current()

        assertEquals(
            DeviceLocation(City.Default.latitude, City.Default.longitude),
            location,
        )
    }

    @Test
    fun `unknown city id falls back to the capital`() = runTest {
        val location = provider(FakeLocationSource(), cityId = "atlantis").current()

        assertEquals(
            DeviceLocation(City.Default.latitude, City.Default.longitude),
            location,
        )
    }

    private suspend fun provider(
        source: FakeLocationSource,
        cityId: String?,
    ): RequestLocationProvider {
        val settings = SettingsDataStore(newDataStore())
        cityId?.let { settings.setCityId(it) }
        return DefaultRequestLocationProvider(locationSource = source, settings = settings)
    }

    // --- AndroidLocationSource: возраст фикса (issue #348) ---

    @Test
    fun `a fresh fix within the age limit is used as is`() = runTest {
        val fixedNow = Instant.parse("2026-01-01T12:00:00Z")
        setLastKnownLocation(
            latitude = 40.10,
            longitude = 65.20,
            time = fixedNow.minusMillis(AndroidLocationSource.MAX_AGE.toMillis() / 2).toEpochMilli(),
        )

        val location = androidSource(Clock.fixed(fixedNow, ZoneOffset.UTC)).lastKnown()

        assertEquals(DeviceLocation(40.10, 65.20), location)
    }

    @Test
    fun `a fix older than the age limit is dropped`() = runTest {
        // Позиция недельной давности из другого города не должна уйти в
        // X-Geo-* как будто это текущее местоположение.
        val fixedNow = Instant.parse("2026-01-01T12:00:00Z")
        setLastKnownLocation(
            latitude = 40.10,
            longitude = 65.20,
            time = fixedNow.minus(AndroidLocationSource.MAX_AGE).minusSeconds(1).toEpochMilli(),
        )

        val location = androidSource(Clock.fixed(fixedNow, ZoneOffset.UTC)).lastKnown()

        assertEquals(null, location)
    }

    private fun setLastKnownLocation(latitude: Double, longitude: Double, time: Long) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val manager = context.getSystemService(LocationManager::class.java)
        val shadowManager = shadowOf(manager)
        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            this.time = time
        }
        shadowManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, location)
    }

    private fun androidSource(clock: Clock): LocationSource =
        AndroidLocationSource(context = ApplicationProvider.getApplicationContext(), clock = clock)
}
