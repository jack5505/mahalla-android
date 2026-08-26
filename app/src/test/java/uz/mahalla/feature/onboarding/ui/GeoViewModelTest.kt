package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.io.IOException

/**
 * Геолокация (3.6). Главное требование ТЗ: отказ в разрешении не должен быть
 * тупиком — пользователь выбирает город и продолжает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeoViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val onboardingRepository = FakeOnboardingRepository()

    private fun viewModel() = GeoViewModel(onboardingRepository)

    @Test
    fun `the screen starts with an explanation`() {
        val state = viewModel().state.value

        assertEquals(GeoStage.Explain, state.stage)
        assertFalse(state.permissionDenied)
        assertEquals(City.entries, state.cities)
    }

    @Test
    fun `allow asks the system for the permission`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.AllowRequested)

        assertEquals(GeoEffect.RequestLocationPermission, viewModel.effects.first())
    }

    @Test
    fun `granted permission finishes the onboarding step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.PermissionResult(granted = true))

        assertEquals(GeoEffect.Finished, viewModel.effects.first())
        assertNull("город не нужен — координаты есть", onboardingRepository.current.cityId)
    }

    @Test
    fun `denied permission opens the city picker instead of a dead end`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.PermissionResult(granted = false))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(GeoStage.CityPicker, state.stage)
        assertTrue(state.permissionDenied)
    }

    @Test
    fun `manual choice is available without asking for the permission`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.ChooseCityRequested)
        advanceUntilIdle()

        assertEquals(GeoStage.CityPicker, viewModel.state.value.stage)
        assertFalse("разрешение не спрашивали", viewModel.state.value.permissionDenied)
    }

    @Test
    fun `selecting a city stores it and finishes the step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        viewModel.onEvent(GeoEvent.PermissionResult(granted = false))

        viewModel.onEvent(GeoEvent.CitySelected(City.SAMARKAND))
        val effect = viewModel.effects.first()

        assertEquals(GeoEffect.Finished, effect)
        assertEquals("samarkand", onboardingRepository.current.cityId)
        assertEquals(City.SAMARKAND, viewModel.state.value.selectedCity)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `a failed city write still finishes the step`() = runTest(mainDispatcherRule.dispatcher) {
        onboardingRepository.writeFailure = IOException("нет места")
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.CitySelected(City.SAMARKAND))
        val effect = viewModel.effects.first()

        // Последний шаг онбординга не должен запирать пользователя из-за
        // настройки: город меняется и в профиле.
        assertEquals(GeoEffect.Finished, effect)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `city ids round trip through storage`() {
        // Каталог читает город строкой из DataStore — сопоставление обязано
        // работать в обе стороны, иначе выбор молча теряется.
        City.entries.forEach { city ->
            assertEquals(city, City.fromId(city.id))
        }
        assertNull(City.fromId("atlantis"))
        assertNull(City.fromId(null))
    }
}
