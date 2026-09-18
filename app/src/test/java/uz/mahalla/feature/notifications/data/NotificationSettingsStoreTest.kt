package uz.mahalla.feature.notifications.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.QuietHours
import java.io.File

/**
 * Настройки уведомлений в DataStore (эпик 11). Robolectric — как и остальным
 * тестам DataStore в проекте.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationSettingsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `everything is enabled and loud by default`() = runTest {
        val store = NotificationSettingsStore(newDataStore())

        val settings = store.current()

        NotificationCategory.entries.forEach { assertTrue(settings.isEnabled(it)) }
        assertFalse(settings.quietHours.enabled)
        assertEquals(QuietHours.DEFAULT_FROM, settings.quietHours.from)
        assertEquals(QuietHours.DEFAULT_TO, settings.quietHours.to)
    }

    @Test
    fun `muted category survives and does not touch the others`() = runTest {
        val store = NotificationSettingsStore(newDataStore())

        store.setCategoryEnabled(NotificationCategory.Marketing, enabled = false)

        val settings = store.current()
        assertFalse(settings.isEnabled(NotificationCategory.Marketing))
        assertTrue(settings.isEnabled(NotificationCategory.Orders))
    }

    @Test
    fun `turning a category back on removes it from the muted set`() = runTest {
        val store = NotificationSettingsStore(newDataStore())

        store.setCategoryEnabled(NotificationCategory.Queue, enabled = false)
        store.setCategoryEnabled(NotificationCategory.Queue, enabled = true)

        assertEquals(emptySet<NotificationCategory>(), store.current().mutedCategories)
    }

    /** Границы пишутся по отдельности: одна не должна затирать другую. */
    @Test
    fun `writing one bound leaves the other alone`() = runTest {
        val store = NotificationSettingsStore(newDataStore())

        store.setQuietHoursFrom(21 * 60)
        store.setQuietHoursTo(7 * 60)
        store.setQuietHoursFrom(20 * 60)

        val quiet = store.current().quietHours
        assertEquals(20 * 60, quiet.from)
        assertEquals(7 * 60, quiet.to)
    }

    @Test
    fun `quiet hours are stored and normalized`() = runTest {
        val store = NotificationSettingsStore(newDataStore())

        store.setQuietHoursEnabled(true)
        store.setQuietHoursFrom(23 * 60)
        // 24:00 — это полночь, а не «конец суток»: иначе интервал стал бы
        // пустым при следующем чтении.
        store.setQuietHoursTo(24 * 60)

        val quiet = store.current().quietHours
        assertTrue(quiet.enabled)
        assertEquals(23 * 60, quiet.from)
        assertEquals(0, quiet.to)
    }

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "notifications.preferences_pb") },
    )
}
