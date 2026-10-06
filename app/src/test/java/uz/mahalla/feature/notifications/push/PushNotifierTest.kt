package uz.mahalla.feature.notifications.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uz.mahalla.MainActivity
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.notifications.data.NotificationSettingsStore
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.QuietHours
import java.io.File
import java.time.LocalTime

/**
 * Показ пуша в системной шторке (эпик 11, issue #397).
 *
 * Robolectric: `NotificationManager` и `PendingIntent` настоящие (их теневые
 * реализации), а не mock — так же, как остальные тесты DataStore/Android API в
 * проекте.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushNotifierTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantPostNotifications() {
        // API 33+: без этого разрешения `PushGate` сам ничего не показал бы —
        // большинству тестов ниже разрешение не интересно, и оно выдано заранее.
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `an order push posts one notification in its category channel`() = runTest {
        newNotifier().show(orderPush())

        assertEquals(1, shadowOf(manager).size())
        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertEquals(NotificationCategory.Orders.id, notification.channelId)
    }

    @Test
    fun `tapping the notification opens the deep link on MainActivity with an immutable pending intent`() =
        runTest {
            val message = orderPush()
            newNotifier().show(message)

            val notification = shadowOf(manager).getNotification(message.tag, NOTIFICATION_ID)
            val shadowPendingIntent = shadowOf(notification.contentIntent)
            val intent = shadowPendingIntent.savedIntent

            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Uri.parse(message.deepLink), intent.data)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(shadowPendingIntent.isImmutable)
        }

    /**
     * Запасной заголовок берётся на языке из профиля, а не системы: устройство
     * здесь притворяется русским (`qualifiers = "ru"`), а в профиле стоит uz —
     * заголовок обязан остаться узбекским.
     */
    @Test
    @Config(qualifiers = "ru")
    fun `fallback title uses the app language, not the system one`() = runTest {
        newNotifier(language = AppLanguage.UZBEK).show(titlelessPush())

        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertEquals("Bildirishnoma", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /** Та же проверка в другую сторону: устройство на uz, профиль — на ru. */
    @Test
    @Config(qualifiers = "uz")
    fun `fallback title switches to russian when the profile language is russian`() = runTest {
        newNotifier(language = AppLanguage.RUSSIAN).show(titlelessPush())

        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertEquals("Уведомление", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `a push with a title keeps it instead of the fallback`() = runTest {
        val message = orderPush(title = "Buyurtma qabul qilindi")
        newNotifier().show(message)

        val notification = shadowOf(manager).getNotification(message.tag, NOTIFICATION_ID)
        assertEquals("Buyurtma qabul qilindi", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `two pushes with different tags post two notifications with different pending intents`() = runTest {
        val notifier = newNotifier()
        val first = orderPush(tag = "n-1", entityId = "o-1")
        val second = orderPush(tag = "n-2", entityId = "o-2")

        notifier.show(first)
        notifier.show(second)

        assertEquals(2, shadowOf(manager).size())
        val firstIntent = shadowOf(manager).getNotification(first.tag, NOTIFICATION_ID).contentIntent
        val secondIntent = shadowOf(manager).getNotification(second.tag, NOTIFICATION_ID).contentIntent
        assertNotEquals(
            shadowOf(firstIntent).requestCode,
            shadowOf(secondIntent).requestCode,
        )
    }

    @Test
    fun `redelivery of the same tag replaces the notification instead of adding one`() = runTest {
        val notifier = newNotifier()
        notifier.show(orderPush(title = "Birinchi"))
        notifier.show(orderPush(title = "Ikkinchi"))

        assertEquals(1, shadowOf(manager).size())
        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertEquals("Ikkinchi", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /**
     * `NotificationCompat.setSilent(true)` (без своего флага в `Notification`)
     * гасит звук и вибрацию через `setSound(null)`/`setVibrate(null)` и переводит
     * канал в `GROUP_ALERT_SUMMARY` — на это и смотрим, раз отдельного булева
     * поля на итоговом объекте нет.
     */
    @Test
    fun `quiet hours show the push without sound`() = runTest {
        val decision = newNotifier(quietHours = QuietHours(enabled = true))
            .show(orderPush(), now = LocalTime.of(2, 30))

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = true), decision)
        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
    }

    @Test
    fun `outside quiet hours the push sounds normally`() = runTest {
        val decision = newNotifier(quietHours = QuietHours(enabled = true))
            .show(orderPush(), now = LocalTime.of(9, 0))

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = false), decision)
        val notification = shadowOf(manager).getNotification(TAG, NOTIFICATION_ID)
        assertEquals(Notification.GROUP_ALERT_ALL, notification.groupAlertBehavior)
    }

    @Test
    fun `a suppressed push posts nothing`() = runTest {
        val decision = newNotifier(mutedCategories = setOf(NotificationCategory.Orders)).show(orderPush())

        assertEquals(PushDecision.Suppressed(PushSuppression.CategoryMuted), decision)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    @Config(sdk = [33])
    fun `on API 33 without POST_NOTIFICATIONS the push is not shown`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val decision = newNotifier().show(orderPush())

        assertEquals(PushDecision.Suppressed(PushSuppression.PermissionRevoked), decision)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    @Config(sdk = [32])
    fun `on API 32 the push is shown without the runtime permission`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val decision = newNotifier().show(orderPush())

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = false), decision)
        assertEquals(1, shadowOf(manager).size())
    }

    private suspend fun newNotifier(
        language: AppLanguage = AppLanguage.UZBEK,
        mutedCategories: Set<NotificationCategory> = emptySet(),
        quietHours: QuietHours = QuietHours(),
    ): PushNotifier {
        val appSettings = SettingsDataStore(newDataStore("settings"))
        appSettings.setLanguage(language)

        val notificationSettingsStore = NotificationSettingsStore(newDataStore("notifications"))
        mutedCategories.forEach { notificationSettingsStore.setCategoryEnabled(it, enabled = false) }
        if (quietHours.enabled) {
            notificationSettingsStore.setQuietHoursEnabled(true)
            notificationSettingsStore.setQuietHoursFrom(quietHours.fromMinuteOfDay)
            notificationSettingsStore.setQuietHoursTo(quietHours.toMinuteOfDay)
        }

        val channels = NotificationChannels(context, appSettings)
        return PushNotifier(context, channels, notificationSettingsStore, appSettings)
    }

    private fun newDataStore(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
    )

    private fun orderPush(
        title: String? = null,
        entityId: String = "o-1",
        tag: String = TAG,
    ): PushMessage {
        val data = buildMap {
            put("id", tag)
            put("type", "ORDER_PLACED")
            put("entityId", entityId)
            if (title != null) put("title", title)
        }
        return PushMessage.of(data = data)
    }

    private fun titlelessPush(): PushMessage = orderPush(title = null)

    private companion object {
        const val TAG = "n-1"
        const val NOTIFICATION_ID = 1
    }
}
