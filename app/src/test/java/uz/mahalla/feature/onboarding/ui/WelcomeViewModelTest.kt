package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Welcome (3.1): выбор языка до входа. Он должен и сохраняться, и
 * применяться — на API < 33 применение означает пересоздание Activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `state follows the stored language`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeOnboardingRepository(AppSettings(language = AppLanguage.RUSSIAN))
        val viewModel = WelcomeViewModel(repository, FakeLocaleManager(needsRecreate = false))

        advanceUntilIdle()

        assertEquals(AppLanguage.RUSSIAN, viewModel.state.value.language)
    }

    @Test
    fun `selecting a language stores it`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeOnboardingRepository()
        val localeManager = FakeLocaleManager(needsRecreate = false)
        val viewModel = WelcomeViewModel(repository, localeManager)

        viewModel.onEvent(WelcomeEvent.LanguageSelected(AppLanguage.UZBEK))
        advanceUntilIdle()

        assertEquals(AppLanguage.UZBEK, repository.current.language)
        assertEquals(listOf(AppLanguage.UZBEK), localeManager.applied)
        assertEquals(AppLanguage.UZBEK, viewModel.state.value.language)
    }

    @Test
    fun `on old android versions the activity is recreated`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = WelcomeViewModel(
            FakeOnboardingRepository(),
            FakeLocaleManager(needsRecreate = true),
        )

        viewModel.onEvent(WelcomeEvent.LanguageSelected(AppLanguage.RUSSIAN))

        assertEquals(WelcomeEffect.RecreateActivity, viewModel.effects.first())
    }

    private class FakeLocaleManager(private val needsRecreate: Boolean) : AppLocaleManager {
        val applied = mutableListOf<AppLanguage>()

        override fun apply(language: AppLanguage): Boolean {
            applied += language
            return needsRecreate
        }

        override fun systemApplied(): AppLanguage? = null
    }
}
