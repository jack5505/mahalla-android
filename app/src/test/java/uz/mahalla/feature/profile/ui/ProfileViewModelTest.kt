package uz.mahalla.feature.profile.ui

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.testutil.FakeHttpInspector
import uz.mahalla.testutil.MainDispatcherRule
import java.io.File

/**
 * Профиль: строка «сетевые запросы» (issue #30).
 *
 * Под Robolectric из-за DataStore и [Intent] — настройки в профиле настоящие.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProfileViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `inspector row opens the traffic screen`() = runTest(mainDispatcherRule.dispatcher) {
        val intent = Intent("uz.mahalla.test.INSPECTOR")
        val viewModel = viewModel(FakeHttpInspector(intent = intent))

        assertTrue(viewModel.state.value.httpInspectorAvailable)
        viewModel.onEvent(ProfileEvent.HttpInspectorRequested)

        assertEquals(ProfileEffect.OpenHttpInspector(intent), viewModel.effects.first())
    }

    @Test
    fun `release build has no inspector row`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(FakeHttpInspector(isAvailable = false))

        viewModel.onEvent(ProfileEvent.HttpInspectorRequested)
        // Событие из устаревшего состояния экрана не должно ни падать, ни
        // отдавать эффект: следующий в очереди — смена языка.
        viewModel.onEvent(ProfileEvent.LanguageSelected(AppLanguage.RUSSIAN))

        assertFalse(viewModel.state.value.httpInspectorAvailable)
        assertEquals(ProfileEffect.RecreateActivity, viewModel.effects.first())
    }

    private fun viewModel(inspector: HttpInspector) = ProfileViewModel(
        settingsDataStore = SettingsDataStore(newDataStore()),
        localeManager = RecreatingLocaleManager,
        httpInspector = inspector,
    )

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "profile-vm.preferences_pb") },
    )

    /** API < 33: смену языка применяет пересоздание Activity. */
    private object RecreatingLocaleManager : AppLocaleManager {
        override fun apply(language: AppLanguage): Boolean = true
        override fun systemApplied(): AppLanguage? = null
    }
}
