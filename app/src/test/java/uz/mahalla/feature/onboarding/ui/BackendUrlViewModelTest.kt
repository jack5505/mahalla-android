package uz.mahalla.feature.onboarding.ui

import android.app.Application
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendCheck
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.network.tls.ServerCertificate
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.testutil.FakeBackendReachability
import uz.mahalla.testutil.FakeCleartextPolicy
import uz.mahalla.testutil.FakeHttpInspector
import uz.mahalla.testutil.MainDispatcherRule
import java.io.File

/**
 * Экран ввода адреса бэкенда (issue #26).
 *
 * Под Robolectric из-за DataStore: адрес обязан именно сохраняться, поэтому
 * хранилище настоящее, а «в сети или нет» — фейк.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackendUrlViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Unconfined: эффект и запись проверяются сразу после события. */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val reachability = FakeBackendReachability()
    private val cleartextPolicy = FakeCleartextPolicy()

    @Test
    fun `field starts with the current address`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        assertEquals(BUILD_URL, viewModel.state.value.url)
        assertEquals(BUILD_URL, viewModel.state.value.defaultUrl)
    }

    @Test
    fun `address is normalized before saving`() = runTest(mainDispatcherRule.dispatcher) {
        val settings = settingsDataStore()
        val store = store(settings)
        val viewModel = viewModel(store)

        viewModel.onEvent(BackendUrlEvent.UrlChanged(" 192.168.0.10:8080 "))
        viewModel.onEvent(BackendUrlEvent.Submit)
        // Эффект приходит после записи — иначе проверка обгоняет DataStore.
        viewModel.effects.first()

        assertEquals("http://192.168.0.10:8080/", settings.current().backendBaseUrl)
        assertEquals("запросы уходят на новый адрес", "http://192.168.0.10:8080/", store.current)
        assertEquals(listOf("http://192.168.0.10:8080/"), reachability.checked)
    }

    @Test
    fun `saved address reports the effect`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(BackendUrlEvent.UrlChanged("https://api.mahalla.uz"))

        viewModel.onEvent(BackendUrlEvent.Submit)

        assertEquals(BackendUrlEffect.Saved, viewModel.effects.first())
    }

    @Test
    fun `broken address is not saved and is not even checked`() =
        runTest(mainDispatcherRule.dispatcher) {
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))

            viewModel.onEvent(BackendUrlEvent.UrlChanged("ws://api.mahalla.uz"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            assertEquals(BackendUrlError.INVALID, viewModel.state.value.error)
            assertNull(settings.current().backendBaseUrl)
            assertTrue("сеть трогать незачем", reachability.checked.isEmpty())
        }

    @Test
    fun `http is refused when the build forbids cleartext`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Release-сборка режет http (network-security-config): сохранить
            // такой адрес значит запереть пользователя без единой подсказки.
            cleartextPolicy.allowCleartext = false
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))

            viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.10:8080"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            assertEquals(BackendUrlError.CLEARTEXT_BLOCKED, viewModel.state.value.error)
            assertNull(settings.current().backendBaseUrl)
            assertTrue("до сети дело не доходит", reachability.checked.isEmpty())
        }

    @Test
    fun `a blocked http address is not saved by the second tap either`() =
        runTest(mainDispatcherRule.dispatcher) {
            // «Всё равно сохранить» обходит молчащий сервер, но не запрет
            // сборки: по такому адресу ни один запрос не уйдёт.
            cleartextPolicy.allowCleartext = false
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))
            viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.10:8080"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            viewModel.onEvent(BackendUrlEvent.Submit)

            assertEquals(BackendUrlError.CLEARTEXT_BLOCKED, viewModel.state.value.error)
            assertNull(settings.current().backendBaseUrl)
        }

    @Test
    fun `https passes on a build that forbids cleartext`() =
        runTest(mainDispatcherRule.dispatcher) {
            cleartextPolicy.allowCleartext = false
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))

            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://api.mahalla.uz"))
            viewModel.onEvent(BackendUrlEvent.Submit)
            viewModel.effects.first()

            assertEquals("https://api.mahalla.uz/", settings.current().backendBaseUrl)
        }

    @Test
    fun `silent server does not save the address on the first tap`() =
        runTest(mainDispatcherRule.dispatcher) {
            reachability.reachable = false
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))

            viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.10:8080"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            assertEquals(BackendUrlError.UNREACHABLE, viewModel.state.value.error)
            assertNull(settings.current().backendBaseUrl)
            assertFalse("крутилка снята", viewModel.state.value.checking)
        }

    @Test
    fun `second tap saves an unreachable address`() = runTest(mainDispatcherRule.dispatcher) {
        // Сервер может не отвечать на HEAD или подняться позже — запирать
        // пользователя на первом экране из-за проверки нельзя.
        reachability.reachable = false
        val settings = settingsDataStore()
        val viewModel = viewModel(store(settings))
        viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.10:8080"))
        viewModel.onEvent(BackendUrlEvent.Submit)

        viewModel.onEvent(BackendUrlEvent.Submit)
        viewModel.effects.first()

        assertEquals("http://192.168.0.10:8080/", settings.current().backendBaseUrl)
        assertEquals("адрес проверялся один раз", 1, reachability.checked.size)
    }

    @Test
    fun `editing the address requires a new check`() = runTest(mainDispatcherRule.dispatcher) {
        reachability.reachable = false
        val viewModel = viewModel()
        viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.10:8080"))
        viewModel.onEvent(BackendUrlEvent.Submit)

        viewModel.onEvent(BackendUrlEvent.UrlChanged("192.168.0.11:8080"))

        assertNull("ошибка старого адреса стёрта", viewModel.state.value.error)
        assertFalse(viewModel.state.value.checked)
    }

    @Test
    fun `default address can be restored`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(BackendUrlEvent.UrlChanged("ws://api.mahalla.uz"))
        viewModel.onEvent(BackendUrlEvent.Submit)

        viewModel.onEvent(BackendUrlEvent.DefaultRequested)

        assertEquals(BUILD_URL, viewModel.state.value.url)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `inspector button opens the traffic screen`() = runTest(mainDispatcherRule.dispatcher) {
        // До входа профиль недоступен, а посмотреть запрос кода из SMS нужно
        // именно здесь (issue #30).
        val intent = Intent("uz.mahalla.test.INSPECTOR")
        val viewModel = viewModel(inspector = FakeHttpInspector(intent = intent))

        assertTrue(viewModel.state.value.httpInspectorAvailable)
        viewModel.onEvent(BackendUrlEvent.HttpInspectorRequested)

        assertEquals(BackendUrlEffect.OpenHttpInspector(intent), viewModel.effects.first())
    }

    @Test
    fun `build without an inspector has no button and no effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(inspector = FakeHttpInspector(isAvailable = false))

            viewModel.onEvent(BackendUrlEvent.HttpInspectorRequested)
            // Сохранение доедет до effects первым, только если инспектор молчит.
            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://api.mahalla.uz"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            assertFalse(viewModel.state.value.httpInspectorAvailable)
            assertEquals(BackendUrlEffect.Saved, viewModel.effects.first())
        }

    @Test
    fun `an untrusted certificate is not saved and is reported as such`() =
        runTest(mainDispatcherRule.dispatcher) {
            // issue #32: сервер на месте, но handshake рвётся. Сохранять адрес
            // нельзя — по нему не уйдёт ни один запрос.
            reachability.result = BackendCheck.UntrustedCertificate(CERTIFICATE)
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))

            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://189.74.96.232"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            assertEquals(
                BackendUrlError.CERTIFICATE_UNTRUSTED,
                viewModel.state.value.error,
            )
            assertEquals(CERTIFICATE, viewModel.state.value.certificate)
            assertTrue(viewModel.state.value.canTrustCertificate)
            assertNull(settings.current().backendBaseUrl)
        }

    @Test
    fun `trusting the certificate pins it and saves the address`() =
        runTest(mainDispatcherRule.dispatcher) {
            reachability.result = BackendCheck.UntrustedCertificate(CERTIFICATE)
            val settings = settingsDataStore()
            val pin = BackendCertificatePin(settings)
            val viewModel = viewModel(store(settings), certificatePin = pin)
            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://189.74.96.232"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            // С записанным пином handshake проходит — так же, как в бою:
            // проверка идёт тем же клиентом, что и запросы приложения.
            reachability.result = BackendCheck.Reachable
            viewModel.onEvent(BackendUrlEvent.TrustCertificateRequested)
            viewModel.effects.first()

            assertEquals(CERTIFICATE.sha256, pin.pinnedFingerprint())
            assertEquals(CERTIFICATE.sha256, settings.current().backendCertificatePin)
            assertEquals("https://189.74.96.232/", settings.current().backendBaseUrl)
            assertNull("сертификат принят — показывать нечего", viewModel.state.value.certificate)
        }

    @Test
    fun `the address is checked again after trusting the certificate`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Доверие — не «сохранить на слово»: сертификат мог смениться
            // между проверкой и подтверждением.
            reachability.result = BackendCheck.UntrustedCertificate(CERTIFICATE)
            val settings = settingsDataStore()
            val viewModel = viewModel(store(settings))
            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://189.74.96.232"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            viewModel.onEvent(BackendUrlEvent.TrustCertificateRequested)
            // Запись пина уводит корутину с потока теста: ждём ответа проверки.
            viewModel.state.first { it.certificate != null }

            assertEquals("адрес проверен второй раз", 2, reachability.checked.size)
            assertEquals(
                BackendUrlError.CERTIFICATE_UNTRUSTED,
                viewModel.state.value.error,
            )
            assertNull(settings.current().backendBaseUrl)
        }

    @Test
    fun `there is nothing to trust before a check`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(BackendUrlEvent.TrustCertificateRequested)

            assertFalse(viewModel.state.value.canTrustCertificate)
            assertTrue("сеть трогать незачем", reachability.checked.isEmpty())
        }

    @Test
    fun `editing the address forgets the certificate`() =
        runTest(mainDispatcherRule.dispatcher) {
            reachability.result = BackendCheck.UntrustedCertificate(CERTIFICATE)
            val viewModel = viewModel()
            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://189.74.96.232"))
            viewModel.onEvent(BackendUrlEvent.Submit)

            viewModel.onEvent(BackendUrlEvent.UrlChanged("https://189.74.96.233"))

            assertNull("сертификат относился к прежнему адресу", viewModel.state.value.certificate)
            assertNull(viewModel.state.value.error)
        }

    private fun viewModel(
        store: BackendUrlStore = store(),
        inspector: HttpInspector = FakeHttpInspector(isAvailable = false),
        // Отдельный файл: тестам, где пин не проверяется, хранилище адреса
        // отдавать нельзя — на один файл допустим один экземпляр DataStore.
        certificatePin: BackendCertificatePin =
            BackendCertificatePin(settingsDataStore("certificate-pin-vm")),
    ) = BackendUrlViewModel(store, reachability, cleartextPolicy, inspector, certificatePin)

    private fun store(settings: SettingsDataStore = settingsDataStore()) =
        BackendUrlStore(settings, BUILD_URL)

    private fun settingsDataStore(name: String = "backend-url-vm") =
        SettingsDataStore(newDataStore(name))

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
        )

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"

        /** Самоподписанный сертификат стенда, как в issue #32. */
        val CERTIFICATE = ServerCertificate(
            sha256 = "3A:1F:9C:04:BE:77:12:E5:8D:60:AA:31:4C:D9:02:6B",
            subject = "CN=189.74.96.232",
            issuer = "CN=189.74.96.232",
        )
    }
}
