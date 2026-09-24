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
    fun `a permanently denied permission offers a way into settings`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.PermissionResult(granted = false, permanentlyDenied = true))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.permissionPermanentlyDenied)
    }

    @Test
    fun `a plain denial does not point to settings`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.PermissionResult(granted = false, permanentlyDenied = false))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.permissionPermanentlyDenied)
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

    /**
     * Отметка города шаг не заканчивает (макет 0d): промах по соседней строке
     * до нажатия «Продолжить» можно исправить, а раньше он сразу сохранялся и
     * увозил человека в каталог чужого города.
     */
    @Test
    fun `selecting a city only marks it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(GeoEvent.PermissionResult(granted = false))

        viewModel.onEvent(GeoEvent.CitySelected(City.SAMARKAND))
        viewModel.onEvent(GeoEvent.CitySelected(City.BUKHARA))
        advanceUntilIdle()

        assertEquals(City.BUKHARA, viewModel.state.value.selectedCity)
        assertNull("до подтверждения город не сохраняется", onboardingRepository.current.cityId)
    }

    @Test
    fun `continue stores the marked city and finishes the step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        viewModel.onEvent(GeoEvent.PermissionResult(granted = false))
        viewModel.onEvent(GeoEvent.CitySelected(City.SAMARKAND))

        viewModel.onEvent(GeoEvent.ContinueClicked)
        val effect = viewModel.effects.first()

        assertEquals(GeoEffect.Finished, effect)
        assertEquals("samarkand", onboardingRepository.current.cityId)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `continue without a marked city does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(GeoEvent.ChooseCityRequested)

        viewModel.onEvent(GeoEvent.ContinueClicked)
        advanceUntilIdle()

        assertNull(onboardingRepository.current.cityId)
        assertFalse("шаг не заканчивается вслепую", viewModel.state.value.busy)
        assertEquals(GeoStage.CityPicker, viewModel.state.value.stage)
    }

    @Test
    fun `a failed city write still finishes the step`() = runTest(mainDispatcherRule.dispatcher) {
        onboardingRepository.writeFailure = IOException("нет места")
        val viewModel = viewModel()

        viewModel.onEvent(GeoEvent.CitySelected(City.SAMARKAND))
        viewModel.onEvent(GeoEvent.ContinueClicked)
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
