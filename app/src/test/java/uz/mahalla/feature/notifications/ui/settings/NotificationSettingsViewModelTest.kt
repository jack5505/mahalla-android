package uz.mahalla.feature.notifications.ui.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.notifications.data.NotificationSettingsStore
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings
import uz.mahalla.feature.notifications.domain.QuietHours
import uz.mahalla.feature.notifications.push.NotificationChannels
import java.io.File

/**
 * Экран настроек уведомлений (эпик 11).
 *
 * Robolectric: ViewModel заводит системные каналы — до первого пуша их в
 * настройках телефона нет, и человек, пришедший настраивать уведомления,
 * увидел бы пустой список.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationSettingsViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state mirrors the store`() = runTest {
        val store = store()
        val viewModel = viewModel(store)

        assertTrue(viewModel.state.value.settings.isEnabled(NotificationCategory.Marketing))

        viewModel.onEvent(
            NotificationSettingsEvent.CategoryToggled(
                NotificationCategory.Marketing,
                enabled = false,
            ),
        )

        val settings = viewModel.awaitSettings { !it.isEnabled(NotificationCategory.Marketing) }
        assertFalse(store.current().isEnabled(NotificationCategory.Marketing))
        // Остальные категории не задеты: выключают одну, а не «уведомления».
        assertTrue(settings.isEnabled(NotificationCategory.Orders))
    }

    @Test
    fun `quiet hours are switched on and off`() = runTest {
        val viewModel = viewModel(store())

        viewModel.onEvent(NotificationSettingsEvent.QuietHoursToggled(enabled = true))
        assertTrue(viewModel.awaitSettings { it.quietHours.enabled }.quietHours.enabled)

        viewModel.onEvent(NotificationSettingsEvent.QuietHoursToggled(enabled = false))
        assertFalse(viewModel.awaitSettings { !it.quietHours.enabled }.quietHours.enabled)
    }

    /**
     * Правится ровно одна граница: раньше выбор «начала» затирал бы «конец»
     * значением по умолчанию, и настройка молча съезжала бы.
     */
    @Test
    fun `picking one bound leaves the other alone`() = runTest {
        val viewModel = viewModel(store())

        viewModel.onEvent(
            NotificationSettingsEvent.QuietHoursEditRequested(QuietHoursBound.From),
        )
        assertEquals(QuietHoursBound.From, viewModel.state.value.editing)

        viewModel.onEvent(NotificationSettingsEvent.QuietHoursPicked(QuietHoursBound.From, 23))

        val quiet = viewModel
            .awaitSettings { it.quietHours.from == 23 * QuietHours.MINUTES_IN_HOUR }
            .quietHours
        assertEquals(QuietHours.DEFAULT_TO, quiet.to)
        // Диалог закрывается сам: выбор — это и есть подтверждение.
        assertNull(viewModel.state.value.editing)
    }

    @Test
    fun `picking the second bound keeps the first`() = runTest {
        val viewModel = viewModel(store())

        viewModel.onEvent(NotificationSettingsEvent.QuietHoursPicked(QuietHoursBound.From, 21))
        // Второй выбор берёт первую границу из состояния, поэтому его нельзя
        // отправлять раньше, чем первая доедет из DataStore.
        viewModel.awaitSettings { it.quietHours.from == 21 * QuietHours.MINUTES_IN_HOUR }
        viewModel.onEvent(NotificationSettingsEvent.QuietHoursPicked(QuietHoursBound.To, 7))

        val quiet = viewModel
            .awaitSettings { it.quietHours.to == 7 * QuietHours.MINUTES_IN_HOUR }
            .quietHours
        assertEquals(21 * QuietHours.MINUTES_IN_HOUR, quiet.from)
    }

    @Test
    fun `dismissing the picker changes nothing`() = runTest {
        val viewModel = viewModel(store())

        viewModel.onEvent(NotificationSettingsEvent.QuietHoursEditRequested(QuietHoursBound.To))
        viewModel.onEvent(NotificationSettingsEvent.QuietHoursEditDismissed)

        assertNull(viewModel.state.value.editing)
        assertEquals(QuietHours.DEFAULT_TO, viewModel.state.value.settings.quietHours.to)
    }

    private fun viewModel(store: NotificationSettingsStore) = NotificationSettingsViewModel(
        store = store,
        channels = NotificationChannels(
            context = ApplicationProvider.getApplicationContext(),
            // Названия каналов зависят от выбранного языка (эпик 11): вне
            // Activity его больше неоткуда взять.
            settings = SettingsDataStore(settingsDataStore()),
        ),
    )

    private fun settingsDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "app-settings.preferences_pb") },
    )

    /**
     * Дождаться, пока значение доедет обратно из DataStore. Состояние — зеркало
     * хранилища, а запись в него идёт на настоящем IO-диспетчере: проверять
     * `state.value` сразу после события значит проверять то, что было до него.
     */
    private suspend fun NotificationSettingsViewModel.awaitSettings(
        predicate: (NotificationSettings) -> Boolean,
    ): NotificationSettings = state.first { predicate(it.settings) }.settings

    private fun store() = NotificationSettingsStore(
        PreferenceDataStoreFactory.create(
            produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
        ),
    )
}
