package uz.mahalla.feature.notifications.push

import android.app.Application
import android.app.NotificationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.notifications.domain.NotificationCategory
import java.io.File

/**
 * Каналы уведомлений (эпик 11, issue #397): важность по категории, имя на
 * языке приложения, выключенный пользователем канал не трогается повторным
 * `ensure`.
 *
 * Robolectric действительно хранит `NotificationChannel`, а не заглушает
 * вызов: `getNotificationChannel` после `ensure`/`ensureAll` возвращает тот же
 * канал, что создали.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationChannelsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `ensureAll creates a channel per category with the expected importance`() = runTest {
        newChannels().ensureAll()

        val attentionWorthy = setOf(
            NotificationCategory.Orders,
            NotificationCategory.Queue,
            NotificationCategory.Bookings,
            NotificationCategory.Payments,
        )
        val quiet = setOf(NotificationCategory.Marketing, NotificationCategory.Other)

        attentionWorthy.forEach { category ->
            assertEquals(
                NotificationManager.IMPORTANCE_DEFAULT,
                manager.getNotificationChannel(category.id).importance,
            )
        }
        quiet.forEach { category ->
            assertEquals(
                NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(category.id).importance,
            )
        }
    }

    @Test
    fun `ensureAll names channels in uzbek by default`() = runTest {
        newChannels().ensureAll()

        assertEquals("Buyurtmalar", manager.getNotificationChannel(NotificationCategory.Orders.id).name)
        assertEquals("Navbat", manager.getNotificationChannel(NotificationCategory.Queue.id).name)
        assertEquals("Aksiyalar", manager.getNotificationChannel(NotificationCategory.Marketing.id).name)
    }

    @Test
    fun `channel names switch to the chosen app language`() = runTest {
        newChannels(language = AppLanguage.RUSSIAN).ensureAll()

        assertEquals("Заказы", manager.getNotificationChannel(NotificationCategory.Orders.id).name)
        assertEquals("Очередь", manager.getNotificationChannel(NotificationCategory.Queue.id).name)
    }

    @Test
    fun `a category without a channel yet is treated as enabled`() = runTest {
        assertTrue(newChannels().isEnabled(NotificationCategory.Orders))
    }

    @Test
    fun `a channel the user silenced reports as disabled`() = runTest {
        val channels = newChannels()
        channels.ensure(NotificationCategory.Marketing)

        manager.getNotificationChannel(NotificationCategory.Marketing.id).importance =
            NotificationManager.IMPORTANCE_NONE

        assertFalse(channels.isEnabled(NotificationCategory.Marketing))
    }

    /**
     * `createNotificationChannel` идемпотентен и обновляет только название и
     * описание (KDoc `NotificationChannels`) — повторный `ensure` не должен
     * включать канал, который человек выключил в системных настройках.
     */
    @Test
    fun `re-running ensure does not revive a channel the user disabled`() = runTest {
        val channels = newChannels()
        channels.ensure(NotificationCategory.Marketing)
        manager.getNotificationChannel(NotificationCategory.Marketing.id).importance =
            NotificationManager.IMPORTANCE_NONE

        channels.ensure(NotificationCategory.Marketing)

        assertFalse(channels.isEnabled(NotificationCategory.Marketing))
    }

    private suspend fun newChannels(language: AppLanguage = AppLanguage.UZBEK): NotificationChannels {
        val settings = SettingsDataStore(newDataStore())
        settings.setLanguage(language)
        return NotificationChannels(context, settings)
    }

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
    )
}
