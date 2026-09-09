package uz.mahalla.data.push

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
import uz.mahalla.data.device.AndroidDeviceInfoProvider
import uz.mahalla.data.device.DeviceIdStore
import uz.mahalla.testutil.FakePushTokenProvider
import java.io.File

/**
 * Регистрация и обновление токена пушей (эпик 11).
 *
 * Проверяется главное следствие контракта: **отдельной ручки регистрации у
 * бэкенда нет**, и токен уезжает полем `AuthDeviceInfo.fcmToken` — то есть
 * `DeviceInfoProvider` обязан подставлять его в описание устройства, а иначе
 * пуш не придёт вообще никогда, и понять это по коду негде.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushTokenRegistrarTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `sync stores the token firebase gave`() = runTest {
        val store = PushTokenStore(newDataStore())
        val provider = FakePushTokenProvider(token = "fcm-1")

        PushTokenRegistrar(provider, store).sync()

        assertEquals("fcm-1", store.current())
    }

    /**
     * Firebase не настроен в сборке (нет `google-services.json`) — штатный
     * случай: приложение работает, токена просто нет.
     */
    @Test
    fun `sync without a token leaves the store empty`() = runTest {
        val store = PushTokenStore(newDataStore())

        PushTokenRegistrar(FakePushTokenProvider(token = null), store).sync()

        assertNull(store.current())
    }

    @Test
    fun `new token replaces the stored one`() = runTest {
        val store = PushTokenStore(newDataStore())
        val registrar = PushTokenRegistrar(FakePushTokenProvider(), store)

        registrar.onTokenChanged("fcm-1")
        registrar.onTokenChanged("fcm-2")

        assertEquals("fcm-2", store.current())
    }

    @Test
    fun `blank token is stored as no token at all`() = runTest {
        val store = PushTokenStore(newDataStore())
        val registrar = PushTokenRegistrar(FakePushTokenProvider(), store)

        registrar.onTokenChanged("fcm-1")
        registrar.onTokenChanged("   ")

        assertNull(store.current())
    }

    /**
     * Ради этого всё и хранится: описание устройства собирается заново на
     * каждый запрос авторизации, и токен уезжает вместе с ним.
     */
    @Test
    fun `device descriptor carries the stored token`() = runTest {
        val dataStore = newDataStore()
        val store = PushTokenStore(dataStore)
        val provider = AndroidDeviceInfoProvider(DeviceIdStore(dataStore), store)

        assertNull(provider.current().fcmToken)

        PushTokenRegistrar(FakePushTokenProvider(token = "fcm-42"), store).sync()

        assertEquals("fcm-42", provider.current().fcmToken)
    }

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "push.preferences_pb") },
    )
}
