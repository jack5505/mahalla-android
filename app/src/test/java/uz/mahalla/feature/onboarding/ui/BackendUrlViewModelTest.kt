package uz.mahalla.feature.onboarding.ui

import android.app.Application
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
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.testutil.FakeBackendReachability
import uz.mahalla.testutil.FakeCleartextPolicy
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

    private fun viewModel(store: BackendUrlStore = store()) =
        BackendUrlViewModel(store, reachability, cleartextPolicy)

    private fun store(settings: SettingsDataStore = settingsDataStore()) =
        BackendUrlStore(settings, BUILD_URL)

    private fun settingsDataStore() = SettingsDataStore(newDataStore())

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "backend-url-vm.preferences_pb") },
    )

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"
    }
}
