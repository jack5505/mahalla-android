package uz.mahalla.data.location

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.testutil.FakeLocationSource
import java.io.File

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
}
