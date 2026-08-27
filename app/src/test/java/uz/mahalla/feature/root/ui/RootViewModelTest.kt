package uz.mahalla.feature.root.ui

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.ThemeMode
import uz.mahalla.feature.onboarding.data.DataStoreOnboardingRepository
import uz.mahalla.testutil.FakeAuthRepository
import java.io.File

/**
 * Стартовый пункт графа навигации (эпик 1.2/1.6).
 *
 * Настройки — живой flow, а `NavHost` пересобирает граф при смене
 * `startDestination` и сбрасывает back stack. Значит пункт старта обязан быть
 * посчитан один раз и не реагировать на последующие эмиссии.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RootViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope живёт на Dispatchers.Main.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh install starts with onboarding`() = runTest {
        val viewModel = viewModel(SettingsDataStore(newDataStore()))

        val ready = viewModel.awaitReady()
        assertTrue(ready.startWithOnboarding)
        assertFalse("сессии нет — начинаем с welcome", ready.resumeOnboardingAtPin)
    }

    @Test
    fun `fresh install asks for the backend address first`() = runTest {
        // Без адреса бэкенда (issue #26) ни один запрос не уйдёт, поэтому
        // ввод адреса стоит перед онбордингом.
        val ready = viewModel(SettingsDataStore(newDataStore())).awaitReady()

        assertTrue(ready.needsBackendUrl)
        assertTrue(ready.backendUrlOverrideEnabled)
    }

    @Test
    fun `a saved backend address is not asked again`() = runTest {
        val settings = SettingsDataStore(newDataStore())
        settings.setBackendBaseUrl("http://192.168.0.10:8080/")

        val ready = viewModel(settings).awaitReady()

        assertFalse(ready.needsBackendUrl)
    }

    @Test
    fun `a build without the override never asks for the address`() = runTest {
        // Release-сборка ходит на адрес из BuildConfig: экран спрятан, и
        // стартовать с него нельзя, иначе пользователь упрётся в него навсегда.
        val settings = SettingsDataStore(newDataStore())

        val ready = viewModel(settings, overrideEnabled = false).awaitReady()

        assertFalse(ready.needsBackendUrl)
        assertFalse(ready.backendUrlOverrideEnabled)
        assertTrue("дальше обычный старт", ready.startWithOnboarding)
    }

    @Test
    fun `saving the backend address does not move the start destination`() = runTest {
        // Экран адреса уходит навигацией; пересчёт стартового пункта на новой
        // эмиссии настроек пересобрал бы граф и сбросил стек.
        val settings = SettingsDataStore(newDataStore())
        val viewModel = viewModel(settings)
        assertTrue(viewModel.awaitReady().needsBackendUrl)

        settings.setBackendBaseUrl("http://192.168.0.10:8080/")

        val latest = viewModel.state
            .first {
                (it as? RootUiState.Ready)?.settings?.backendBaseUrl != null
            } as RootUiState.Ready
        assertTrue("стартовый пункт зафиксирован", latest.needsBackendUrl)
    }

    @Test
    fun `an interrupted onboarding with a session resumes at the pin`() = runTest {
        // Пользователь прошёл SMS и убил приложение на биометрии: повторный
        // код стоит денег, а сессия уже лежит в хранилище.
        val viewModel = viewModel(
            settings = SettingsDataStore(newDataStore()),
            authRepository = FakeAuthRepository(initialAuthorized = true),
        )

        val ready = viewModel.awaitReady()
        assertTrue(ready.startWithOnboarding)
        assertTrue(ready.resumeOnboardingAtPin)
    }

    @Test
    fun `a completed onboarding never resumes at the pin`() = runTest {
        val settings = SettingsDataStore(newDataStore())
        settings.setOnboardingCompleted(true)

        val ready = viewModel(
            settings = settings,
            authRepository = FakeAuthRepository(initialAuthorized = true),
        ).awaitReady()

        assertFalse(ready.startWithOnboarding)
        assertFalse("основной граф начинается с каталога", ready.resumeOnboardingAtPin)
    }

    @Test
    fun `completed onboarding starts in the main graph`() = runTest {
        val settings = SettingsDataStore(newDataStore())
        settings.setOnboardingCompleted(true)

        val viewModel = viewModel(settings)

        assertFalse(viewModel.awaitReady().startWithOnboarding)
    }

    @Test
    fun `changing settings does not move the start destination`() = runTest {
        val settings = SettingsDataStore(newDataStore())
        val viewModel = viewModel(settings)
        assertTrue(viewModel.awaitReady().startWithOnboarding)

        // Ровно те эмиссии, которые раньше пересобирали граф: конец онбординга
        // и смена темы из профиля.
        settings.setOnboardingCompleted(true)
        settings.setThemeMode(ThemeMode.LIGHT)

        val latest = viewModel.state
            .first { (it as? RootUiState.Ready)?.settings?.themeMode == ThemeMode.LIGHT }
            as RootUiState.Ready
        assertTrue("флаг онбординга записан", latest.settings.onboardingCompleted)
        assertEquals(ThemeMode.LIGHT, latest.settings.themeMode)
        assertTrue("стартовый пункт зафиксирован на первой эмиссии", latest.startWithOnboarding)
    }

    private fun viewModel(
        settings: SettingsDataStore,
        authRepository: FakeAuthRepository = FakeAuthRepository(),
        overrideEnabled: Boolean = true,
    ) = RootViewModel(
        settings,
        DataStoreOnboardingRepository(settings),
        authRepository,
        BackendUrlStore(settings, BUILD_URL, overrideEnabled),
        BackendCertificatePin(settings, overrideEnabled),
    )

    private suspend fun RootViewModel.awaitReady(): RootUiState.Ready =
        state.first { it is RootUiState.Ready } as RootUiState.Ready

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "root.preferences_pb") },
    )

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"
    }
}
