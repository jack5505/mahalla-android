package uz.mahalla.data.network

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import java.io.File

/**
 * Пин сертификата (issue #32).
 *
 * Как и адрес бэкенда, отпечаток читается синхронно на потоке OkHttp, поэтому
 * важно и то, что кэш поднимается на старте, и то, что он обновляется сразу
 * при записи: пин нужен уже следующему запросу, а не следующему запуску.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackendCertificatePinTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `without a saved pin nothing is trusted`() = runTest {
        val pin = pin()

        pin.hydrate()

        assertNull(pin.pinnedFingerprint())
    }

    @Test
    fun `hydrate lifts the saved pin into the cache`() = runTest {
        val settings = settingsDataStore()
        settings.setBackendCertificatePin(FINGERPRINT)

        val pin = pin(settings)
        assertNull("до поднятия кэша доверия нет", pin.pinnedFingerprint())

        pin.hydrate()

        assertEquals(FINGERPRINT, pin.pinnedFingerprint())
    }

    @Test
    fun `save applies the pin immediately and persists it`() = runTest {
        val settings = settingsDataStore()
        val pin = pin(settings)

        pin.save(FINGERPRINT)

        assertEquals("доверие действует сразу", FINGERPRINT, pin.pinnedFingerprint())
        assertEquals(FINGERPRINT, settings.current().backendCertificatePin)
    }

    @Test
    fun `confirming a new certificate replaces the previous one`() = runTest {
        val settings = settingsDataStore()
        val pin = pin(settings)
        pin.save(FINGERPRINT)

        pin.save(OTHER_FINGERPRINT)

        assertEquals(OTHER_FINGERPRINT, pin.pinnedFingerprint())
        assertEquals(OTHER_FINGERPRINT, settings.current().backendCertificatePin)
    }

    @Test
    fun `a saved pin survives a restart`() = runTest {
        val settings = settingsDataStore()
        pin(settings).save(FINGERPRINT)

        val restarted = pin(settings)
        restarted.hydrate()

        assertEquals(FINGERPRINT, restarted.pinnedFingerprint())
    }

    @Test
    fun `a build without the override ignores a saved pin`() = runTest {
        // Пин мог приехать из бэкапа debug-установки: релиз проверяет
        // сертификаты только по системным CA.
        val settings = settingsDataStore()
        settings.setBackendCertificatePin(FINGERPRINT)

        val pin = pin(settings, overrideEnabled = false)
        pin.hydrate()

        assertNull(pin.pinnedFingerprint())
    }

    @Test
    fun `a build without the override does not save the pin`() = runTest {
        val settings = settingsDataStore()
        val pin = pin(settings, overrideEnabled = false)

        pin.save(FINGERPRINT)

        assertNull(pin.pinnedFingerprint())
        assertNull(settings.current().backendCertificatePin)
    }

    private fun pin(
        settings: SettingsDataStore = settingsDataStore(),
        overrideEnabled: Boolean = true,
    ) = BackendCertificatePin(settings, overrideEnabled)

    private fun settingsDataStore() = SettingsDataStore(newDataStore())

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "certificate-pin.preferences_pb") },
    )

    private companion object {
        const val FINGERPRINT = "3A:1F:9C:04:BE:77:12:E5:8D:60:AA:31:4C:D9:02:6B"
        const val OTHER_FINGERPRINT = "F8:55:17:E0:9A:24:73:CB:10:8E:42:FD:66:B3:07:91"
    }
}
