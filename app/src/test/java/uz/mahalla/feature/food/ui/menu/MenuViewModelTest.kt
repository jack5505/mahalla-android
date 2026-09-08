package uz.mahalla.feature.food.ui.menu

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeMenuRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine
import uz.mahalla.testutil.menu
import uz.mahalla.testutil.menuItem
import uz.mahalla.testutil.menuOption
import uz.mahalla.testutil.optionGroup

/**
 * Меню заведения (эпик 5.1).
 *
 * Robolectric — из-за `SavedStateHandle.toRoute()`: типизированный маршрут
 * разбирается через настоящий `Bundle`, а в обычном JVM-тесте android.jar
 * заглушен и `placeId` читался бы как `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val menuRepository = FakeMenuRepository()
    private val cartRepository = FakeCartRepository()

    @Test
    fun `menu is loaded for the id from the route`() = runTest {
        val state = viewModel().state.value

        assertTrue(state.menu is ScreenState.Content)
        // Название заведения приходит маршрутом: в ответе меню его нет.
        assertEquals("Osh markazi", state.placeName)
        assertEquals("main", state.visibleCategory?.id)
    }

    @Test
    fun `an empty menu is an empty state, not an empty list`() = runTest {
        menuRepository.menuResult = ApiResult.Success(menu(items = emptyList()))

        assertEquals(ScreenState.Empty, viewModel().state.value.menu)
    }

    @Test
    fun `error state offers a retry that works`() = runTest {
        menuRepository.menuResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        assertEquals(ScreenState.Error(ApiError.NoConnection), viewModel.state.value.menu)

        menuRepository.menuResult = ApiResult.Success(menu())
        viewModel.onEvent(MenuEvent.Retry)

        assertTrue(viewModel.state.value.menu is ScreenState.Content)
    }

    @Test
    fun `an item without options goes straight to the cart`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        assertNull(viewModel.state.value.sheet)
        assertEquals(1, cartRepository.current(PLACE_ID).itemCount)
    }

    @Test
    fun `an item with options opens the sheet preselected`() = runTest {
        menuRepository.menuResult = ApiResult.Success(menu(items = listOf(itemWithOptions())))
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        val sheet = viewModel.state.value.sheet
        assertNotNull(sheet)
        // Обязательная одиночная группа открывается заполненной — иначе кнопка
        // «добавить» выключена на ровном месте.
        assertEquals(setOf("small"), sheet?.selectedOptionIds)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `an item from the stop list cannot be opened at all`() = runTest {
        menuRepository.menuResult = ApiResult.Success(
            menu(items = listOf(menuItem("osh", isAvailable = false))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        assertNull(viewModel.state.value.sheet)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `an item whose required group is entirely in the stop list does not open either`() = runTest {
        // Шторка открывалась, а кнопка «добавить» не включалась никогда: в
        // обязательной группе выбрать было нечего.
        val stopped = optionGroup(
            id = "size",
            minChoices = 1,
            options = listOf(menuOption("small", isAvailable = false)),
        )
        menuRepository.menuResult = ApiResult.Success(
            menu(items = listOf(menuItem("osh", optionGroups = listOf(stopped)))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        assertNull(viewModel.state.value.sheet)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `the sheet price follows the chosen options and the quantity`() = runTest {
        menuRepository.menuResult = ApiResult.Success(menu(items = listOf(itemWithOptions())))
        val viewModel = viewModel()
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        viewModel.onEvent(MenuEvent.OptionToggled("size", "large"))
        viewModel.onEvent(MenuEvent.SheetQuantityChanged(2))

        val sheet = viewModel.state.value.sheet!!
        assertEquals(40_000L, sheet.unitPriceSum)
        assertEquals(80_000L, sheet.totalSum)
    }

    @Test
    fun `adding with an unfilled required group only shows the errors`() = runTest {
        val required = optionGroup(
            id = "extras",
            minChoices = 1,
            maxChoices = 2,
            options = listOf(menuOption("cheese", priceDeltaSum = 5_000)),
        )
        menuRepository.menuResult = ApiResult.Success(
            menu(items = listOf(menuItem("osh", optionGroups = listOf(required)))),
        )
        val viewModel = viewModel()
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        viewModel.onEvent(MenuEvent.AddToCartClicked)

        val sheet = viewModel.state.value.sheet!!
        assertTrue(sheet.validationShown)
        assertFalse(sheet.canAdd)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `a filled sheet adds the line with its options and closes`() = runTest {
        menuRepository.menuResult = ApiResult.Success(menu(items = listOf(itemWithOptions())))
        val viewModel = viewModel()
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))
        viewModel.onEvent(MenuEvent.OptionToggled("size", "large"))
        viewModel.onEvent(MenuEvent.SheetQuantityChanged(3))

        viewModel.onEvent(MenuEvent.AddToCartClicked)

        assertNull(viewModel.state.value.sheet)
        val line = cartRepository.current(PLACE_ID).lines.single()
        assertEquals(setOf("large"), line.optionIds)
        assertEquals(40_000L, line.unitPriceSum)
        assertEquals(3, line.quantity)
        assertEquals("Option large", line.optionsLabel)
    }

    @Test
    fun `adding from another place asks before clearing the cart`() = runTest {
        cartRepository.seed(
            Cart(placeId = "place-2", placeName = "Somsa uyi", lines = listOf(cartLine("somsa"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        assertEquals("Somsa uyi", viewModel.state.value.conflictPlaceName)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `confirming the conflict clears the old cart and adds the item`() = runTest {
        cartRepository.seed(
            Cart(placeId = "place-2", placeName = "Somsa uyi", lines = listOf(cartLine("somsa"))),
        )
        val viewModel = viewModel()
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        viewModel.onEvent(MenuEvent.ConflictConfirmed)

        assertNull(viewModel.state.value.conflictPlaceName)
        assertTrue(cartRepository.current("place-2").isEmpty)
        assertEquals(1, cartRepository.current(PLACE_ID).itemCount)
    }

    @Test
    fun `dismissing the conflict keeps both carts as they were`() = runTest {
        cartRepository.seed(
            Cart(placeId = "place-2", placeName = "Somsa uyi", lines = listOf(cartLine("somsa"))),
        )
        val viewModel = viewModel()
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        viewModel.onEvent(MenuEvent.ConflictDismissed)

        assertNull(viewModel.state.value.conflictPlaceName)
        assertEquals(1, cartRepository.current("place-2").itemCount)
        assertTrue(cartRepository.current(PLACE_ID).isEmpty)
    }

    @Test
    fun `the cart bar follows the draft`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(MenuEvent.ItemClicked("osh"))
        viewModel.onEvent(MenuEvent.ItemClicked("osh"))

        val state = viewModel.state.value
        assertEquals(2, state.cartItemCount)
        assertEquals(60_000L, state.cartTotalSum)
        assertTrue(state.hasCart)
    }

    private fun itemWithOptions() = menuItem(
        id = "osh",
        priceSum = 30_000,
        optionGroups = listOf(
            optionGroup(
                id = "size",
                minChoices = 1,
                maxChoices = 1,
                options = listOf(menuOption("small"), menuOption("large", priceDeltaSum = 10_000)),
            ),
        ),
    )

    private fun viewModel() = MenuViewModel(
        menuRepository = menuRepository,
        cartRepository = cartRepository,
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to PLACE_ID, "placeName" to "Osh markazi"),
        ),
    )

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
