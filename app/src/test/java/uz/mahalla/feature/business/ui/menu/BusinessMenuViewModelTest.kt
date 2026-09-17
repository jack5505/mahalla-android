package uz.mahalla.feature.business.ui.menu

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessMenuItem
import uz.mahalla.feature.business.domain.BusinessMenuSection
import uz.mahalla.feature.business.domain.NewMenuItemError
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Меню и стоп-лист (задача 12.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessMenuViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the menu is loaded with its sections`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))

        val state = viewModel(repository).state.value

        assertEquals(1, (state.menu as ScreenState.Content).data.sections.single().items.size)
    }

    @Test
    fun `an empty menu is an empty state, not an error`() = runTest {
        assertTrue(viewModel(FakeBusinessRepository()).state.value.menu is ScreenState.Empty)
    }

    @Test
    fun `the stop list toggle sends the state known to the app`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessMenuEvent.StopListToggled("i-1"))

        assertEquals(listOf("i-1" to true), repository.toggledItems)
        val menu = (viewModel.state.value.menu as ScreenState.Content).data
        assertTrue(menu.item("i-1")!!.isStopped)
        assertEquals(1, menu.stoppedCount)
    }

    @Test
    fun `an item is brought back from the stop list`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = false)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessMenuEvent.StopListToggled("i-1"))

        assertEquals(listOf("i-1" to false), repository.toggledItems)
        val menu = (viewModel.state.value.menu as ScreenState.Content).data
        assertFalse(menu.item("i-1")!!.isStopped)
    }

    @Test
    fun `a failed toggle keeps the flag and shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        repository.toggleStopListResult = ApiResult.Failure(ApiFailure(ApiError.Forbidden))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessMenuEvent.StopListToggled("i-1"))

        val state = viewModel.state.value
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertTrue((state.menu as ScreenState.Content).data.item("i-1")!!.isAvailable)
    }

    @Test
    fun `a toggle of an unknown item does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessMenuEvent.StopListToggled("i-404"))

        assertTrue(repository.toggledItems.isEmpty())
    }

    /** У заведения обычно один раздел — выбирать не из чего, подставляем сами. */
    @Test
    fun `opening the form preselects the first section`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)

        assertTrue(viewModel.state.value.isFormVisible)
        assertEquals("s-1", viewModel.state.value.form.sectionId)
    }

    @Test
    fun `an invalid form does not reach the server and shows all the errors at once`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)

        viewModel.onEvent(BusinessMenuEvent.SaveClicked)

        assertTrue(repository.createdItems.isEmpty())
        val errors = viewModel.state.value.formErrors
        assertTrue(NewMenuItemError.NameRequired in errors)
        assertTrue(NewMenuItemError.PriceRequired in errors)
        assertTrue(viewModel.state.value.isFormVisible)
    }

    /** `@Min(1000)` у `CreateItemRequest.price` — сказать об этом до запроса честнее. */
    @Test
    fun `a price below the backend minimum is caught on the client`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)
        viewModel.onEvent(BusinessMenuEvent.NameChanged("Osh"))
        viewModel.onEvent(BusinessMenuEvent.PriceChanged("500"))

        viewModel.onEvent(BusinessMenuEvent.SaveClicked)

        assertTrue(repository.createdItems.isEmpty())
        assertTrue(
            NewMenuItemError.PriceTooSmall(NewMenuItemForm.MIN_PRICE_SUM) in
                viewModel.state.value.formErrors,
        )
    }

    /** Цена — только цифры: бэкенд принимает целое, а съеденный символ пугает. */
    @Test
    fun `the price field keeps only digits`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)

        viewModel.onEvent(BusinessMenuEvent.PriceChanged("32 000,50"))

        assertEquals("3200050", viewModel.state.value.form.priceText)
    }

    @Test
    fun `a valid form is sent and the menu is re-read`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        repository.createItemResult = ApiResult.Success(
            menu(item("i-1", available = true), item("i-2", available = true)),
        )
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)
        viewModel.onEvent(BusinessMenuEvent.NameChanged("  Osh  "))
        viewModel.onEvent(BusinessMenuEvent.PriceChanged("32000"))
        viewModel.onEvent(BusinessMenuEvent.HalalChanged(true))

        viewModel.onEvent(BusinessMenuEvent.SaveClicked)

        val sent = repository.createdItems.single()
        assertEquals("Osh", sent.name)
        assertEquals(32_000L, sent.priceOrNull())
        assertEquals("s-1", sent.sectionId)
        assertTrue(sent.isHalal)

        val state = viewModel.state.value
        assertFalse(state.isFormVisible)
        assertFalse(state.isSaving)
        assertEquals(2, (state.menu as ScreenState.Content).data.sections.single().items.size)
    }

    @Test
    fun `a refusal keeps the form open with the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        repository.createItemResult = ApiResult.Failure(ApiFailure(ApiError.Forbidden))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)
        viewModel.onEvent(BusinessMenuEvent.NameChanged("Osh"))
        viewModel.onEvent(BusinessMenuEvent.PriceChanged("32000"))

        viewModel.onEvent(BusinessMenuEvent.SaveClicked)

        val state = viewModel.state.value
        assertTrue(state.isFormVisible)
        assertEquals(ApiError.Forbidden, state.formFailure?.error)
        assertFalse(state.isSaving)
    }

    /**
     * До первой попытки сохранить форма молчит, после — исправляется на
     * глазах: подчёркивать красным недописанное поле значит ругаться на
     * незаконченную мысль.
     */
    @Test
    fun `errors appear only after the first save and clear as the form is fixed`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)

        viewModel.onEvent(BusinessMenuEvent.NameChanged(""))
        assertTrue(viewModel.state.value.formErrors.isEmpty())

        viewModel.onEvent(BusinessMenuEvent.SaveClicked)
        assertTrue(NewMenuItemError.NameRequired in viewModel.state.value.formErrors)

        viewModel.onEvent(BusinessMenuEvent.NameChanged("Osh"))
        assertFalse(NewMenuItemError.NameRequired in viewModel.state.value.formErrors)
    }

    @Test
    fun `closing the form wipes it`() = runTest {
        val repository = FakeBusinessRepository()
        repository.menuResult = ApiResult.Success(menu(item("i-1", available = true)))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessMenuEvent.AddItemClicked)
        viewModel.onEvent(BusinessMenuEvent.NameChanged("Osh"))

        viewModel.onEvent(BusinessMenuEvent.FormDismissed)

        assertFalse(viewModel.state.value.isFormVisible)
        assertEquals(NewMenuItemForm(), viewModel.state.value.form)
    }

    private fun viewModel(repository: FakeBusinessRepository) = BusinessMenuViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to FakeBusinessRepository.PLACE_ID,
                BusinessArgs.PLACE_NAME to "Osh Markazi",
            ),
        ),
    )

    private fun menu(vararg items: BusinessMenuItem) = BusinessMenu(
        sections = listOf(
            BusinessMenuSection(id = "s-1", name = "Issiq taomlar", items = items.toList()),
        ),
    )

    private fun item(id: String, available: Boolean) = BusinessMenuItem(
        id = id,
        name = id,
        priceSum = 32_000,
        isAvailable = available,
    )
}
