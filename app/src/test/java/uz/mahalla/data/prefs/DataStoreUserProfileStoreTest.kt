package uz.mahalla.data.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Профиль в настоящем DataStore, а не в фейке (issue #237): у фейка `save()`
 * подменяет объект целиком, поэтому забытый ключ в `save`/`clear` он не
 * замечает — а именно на этом держится обещание «чужих прав в профиле не
 * останется».
 *
 * На один файл в процессе допустим ровно один экземпляр DataStore, поэтому
 * внутри теста он создаётся один раз и переиспользуется.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataStoreUserProfileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "profile.preferences_pb") },
    )

    @Test
    fun `every field survives a new instance`() = runTest {
        val dataStore = newDataStore()
        val saved = UserProfile(
            id = "u-1",
            phone = "+998901234567",
            fullName = "Alisher Usmonov",
            avatarUrl = "https://cdn.mahalla.uz/a.png",
            serverRole = "FOOD_OWNER",
            verificationStatus = "FULL_VERIFIED",
            accountStatus = "TEMP_BLOCKED",
        )

        DataStoreUserProfileStore(dataStore).save(saved)

        assertEquals(saved, DataStoreUserProfileStore(dataStore).current())
    }

    @Test
    fun `login of another person leaves nothing of the previous one`() = runTest {
        val dataStore = newDataStore()
        val store = DataStoreUserProfileStore(dataStore)
        store.save(
            UserProfile(
                id = "u-1",
                fullName = "Alisher Usmonov",
                serverRole = "FOOD_OWNER",
                verificationStatus = "FULL_VERIFIED",
                accountStatus = "ACTIVE",
            ),
        )

        // Ответ без роли и статусов: чужие права — это лишние строки в меню.
        store.save(UserProfile(id = "u-2", phone = "+998901112233"))

        assertEquals(UserProfile(id = "u-2", phone = "+998901112233"), store.current())
    }

    @Test
    fun `logout wipes the server half too`() = runTest {
        val dataStore = newDataStore()
        val store = DataStoreUserProfileStore(dataStore)
        store.save(
            UserProfile(
                id = "u-1",
                phone = "+998901234567",
                serverRole = "CINEMA_OWNER",
                verificationStatus = "SMS_VERIFIED",
                accountStatus = "ACTIVE",
            ),
        )

        store.clear()

        assertTrue(store.current().isEmpty)
    }

    @Test
    fun `blank values are not stored as an answer of the server`() = runTest {
        val dataStore = newDataStore()
        val store = DataStoreUserProfileStore(dataStore)

        store.save(UserProfile(id = "u-1", serverRole = "  ", accountStatus = ""))

        // Пустая строка — это «сервер промолчал», а не роль с таким именем.
        val stored = store.current()
        assertEquals(null, stored.serverRole)
        assertEquals(null, stored.accountStatus)
    }
}
