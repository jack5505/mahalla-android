package uz.mahalla.feature.role.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import uz.mahalla.data.prefs.DataStoreUserProfileStore
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.UserRole
import java.io.File

/**
 * Роль и анкета покупателя в DataStore (issue #84).
 *
 * Настоящий DataStore, а не фейк: анкета пишется в два хранилища сразу (имя —
 * в профиль, город и адрес — в настройки), и склейка этих записей и есть то,
 * что здесь проверяется.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoleRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `empty storage means no role and an empty form`() = runTest {
        val profile = repository().current()

        assertNull(profile.role)
        assertTrue(profile.customer.isEmpty)
    }

    @Test
    fun `role survives a write and a read`() = runTest {
        val repository = repository()

        repository.selectRole(UserRole.Provider)

        assertEquals(UserRole.Provider, repository.current().role)
    }

    @Test
    fun `saved form lands in both stores and comes back whole`() = runTest {
        val repository = repository()

        val saved = repository.saveCustomer(
            CustomerForm(
                fullName = "  Jahongir Sabirov ",
                city = City.SAMARKAND,
                address = " Registon 4 ",
            ),
        )

        assertTrue(saved)
        val profile = repository.current()
        assertEquals("Jahongir Sabirov", profile.customer.fullName)
        assertEquals(City.SAMARKAND, profile.customer.city)
        assertEquals("Registon 4", profile.customer.address)
        // Анкета покупателя — это и есть выбор роли: спрашивать её отдельно
        // после заполнения незачем.
        assertEquals(UserRole.Customer, profile.role)
    }

    @Test
    fun `name goes to the same profile the header shows`() = runTest {
        val dataStore = newDataStore()
        val profileStore = DataStoreUserProfileStore(dataStore)
        // Профиль приезжает с ответом на вход (issue #61) — анкета правит
        // имя, а номер и id не трогает.
        profileStore.save(UserProfile(id = "u-1", phone = "+998901234567"))
        val repository = DataStoreRoleRepository(SettingsDataStore(dataStore), profileStore)

        repository.saveCustomer(CustomerForm(fullName = "Jahongir", city = City.TASHKENT))

        val stored = profileStore.current()
        assertEquals("Jahongir", stored.fullName)
        assertEquals("u-1", stored.id)
        assertEquals("+998901234567", stored.phone)
    }

    @Test
    fun `empty address is not stored as an address made of spaces`() = runTest {
        val repository = repository()

        repository.saveCustomer(
            CustomerForm(fullName = "Jahongir", city = City.TASHKENT, address = "   "),
        )

        // Пустой адрес не должен подставляться поверх набранного в оформлении
        // заказа.
        assertEquals("", repository.current().customer.address)
        assertFalse(repository.current().customer.isEmpty)
    }

    private fun repository(): DataStoreRoleRepository {
        val dataStore = newDataStore()
        return DataStoreRoleRepository(
            settings = SettingsDataStore(dataStore),
            profileStore = DataStoreUserProfileStore(dataStore),
        )
    }

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.newFolder(), "settings.preferences_pb") },
    )
}
