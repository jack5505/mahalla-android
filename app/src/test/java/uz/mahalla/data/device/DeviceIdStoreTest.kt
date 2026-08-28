package uz.mahalla.data.device

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Идентификатор установки (issue #42): бэкенд заводит по нему сессию
 * устройства, поэтому он обязан быть один и тот же от запуска к запуску.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceIdStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "device.preferences_pb") },
    )

    @Test
    fun `generated identifier survives a new instance`() = runTest {
        val dataStore = newDataStore()

        val first = DeviceIdStore(dataStore).deviceId()
        val second = DeviceIdStore(dataStore).deviceId()

        assertEquals(first, second)
        // Формат не обязателен бэкенду, но случайный UUID исключает совпадения
        // между установками — иначе устройства склеились бы в одну сессию.
        assertNotNull(UUID.fromString(first))
    }

    @Test
    fun `repeated calls return the same value`() = runTest {
        val store = DeviceIdStore(newDataStore())

        assertEquals(store.deviceId(), store.deviceId())
    }

    @Test
    fun `parallel callers do not create two devices`() = runTest {
        val store = DeviceIdStore(newDataStore())

        val ids = (1..8).map { async { store.deviceId() } }.awaitAll()

        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `unreadable storage still yields an identifier`() = runTest {
        // Без идентификатора нельзя даже запросить код из SMS: запирать вход
        // из-за недоступного DataStore нельзя.
        val id = DeviceIdStore(BrokenDataStore()).deviceId()

        assertTrue(id.isNotBlank())
    }

    private class BrokenDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk is gone") }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = throw IOException("disk is gone")
    }
}
