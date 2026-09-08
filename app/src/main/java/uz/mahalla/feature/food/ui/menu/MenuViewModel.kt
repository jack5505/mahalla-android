package uz.mahalla.feature.food.ui.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.MenuRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.MenuOption
import uz.mahalla.feature.food.domain.MenuOptionRules
import uz.mahalla.navigation.MenuRoute
import javax.inject.Inject

/**
 * Меню заведения (эпик 5.1).
 *
 * Позиция без модификаторов кладётся в корзину одним нажатием; с
 * модификаторами — открывает шторку. Промежуточного состояния «положил, а
 * потом выбери размер» нет: заказ, собранный наполовину, сервер не примет.
 *
 * Корзина всегда в рамках одного заведения, поэтому добавление из второго
 * места сначала спрашивает разрешения очистить прежнюю ([MenuState.conflictPlaceName]).
 */
@HiltViewModel
class MenuViewModel @Inject constructor(
    private val menuRepository: MenuRepository,
    private val cartRepository: CartRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<MenuState, MenuEvent, MenuEffect>(MenuState()) {

    private val route: MenuRoute = savedStateHandle.toRoute()

    private val placeId: String = route.placeId

    /** Позиция, ждущая ответа на вопрос «очистить корзину другого заведения?». */
    private var pendingLine: CartLine? = null

    init {
        // Название заведения приходит маршрутом: в ответе `food/.../menu` его
        // нет, а корзина и диалог «корзина другого заведения» без него
        // показывали бы пустые кавычки.
        updateState { copy(placeName = route.placeName) }
        load()
        viewModelScope.launch {
            cartRepository.cart(placeId).collect { cart -> updateState { withCart(cart) } }
        }
    }

    override fun onEvent(event: MenuEvent) {
        when (event) {
            MenuEvent.Retry -> load()

            is MenuEvent.CategorySelected -> updateState { copy(selectedCategoryId = event.categoryId) }

            is MenuEvent.ItemClicked -> onItemClicked(event.itemId)

            MenuEvent.SheetDismissed -> updateState { copy(sheet = null) }

            is MenuEvent.OptionToggled -> onOptionToggled(event.groupId, event.optionId)

            is MenuEvent.SheetQuantityChanged -> updateState {
                val sheet = sheet ?: return@updateState this
                copy(
                    sheet = sheet.copy(
                        quantity = event.quantity.coerceIn(1, CartCalculator.MAX_QUANTITY),
                    ),
                )
            }

            MenuEvent.AddToCartClicked -> onAddToCart()

            MenuEvent.ConflictConfirmed -> onConflictConfirmed()

            MenuEvent.ConflictDismissed -> {
                pendingLine = null
                updateState { copy(conflictPlaceName = null) }
            }

            MenuEvent.CartClicked -> emitEffect(MenuEffect.OpenCart(placeId))
            MenuEvent.BackClicked -> emitEffect(MenuEffect.NavigateBack)
        }
    }

    private fun load() {
        updateState { copy(menu = ScreenState.Loading) }
        viewModelScope.launch {
            when (val result = menuRepository.menu(placeId)) {
                is ApiResult.Failure -> updateState { copy(menu = ScreenState.Error(result.failure)) }
                is ApiResult.Success -> updateState { withMenu(result.data) }
            }
        }
    }

    private fun MenuState.withMenu(menu: Menu): MenuState = copy(
        menu = if (menu.isEmpty) ScreenState.Empty else ScreenState.Content(menu),
        // Категория выбирается один раз: пересчёт на каждой загрузке сбрасывал
        // бы прокрутку человека к первой вкладке.
        selectedCategoryId = selectedCategoryId ?: menu.categories.firstOrNull()?.id,
    )

    private fun MenuState.withCart(cart: Cart): MenuState = copy(
        cartItemCount = cart.itemCount,
        cartTotalSum = CartCalculator.subtotal(cart.lines),
        placeName = cart.placeName.takeIf(String::isNotBlank) ?: placeName,
    )

    private fun onItemClicked(itemId: String) {
        val item = currentState.data?.item(itemId) ?: return
        // Не `isAvailable`: позиция с невыполнимой обязательной группой тоже
        // недоступна, и открывать шторку с кнопкой, которая не включится, незачем.
        if (!item.isOrderable) return

        if (item.hasOptions) {
            updateState {
                copy(
                    sheet = OptionsSheetState(
                        item = item,
                        selectedOptionIds = MenuOptionRules.defaultSelection(item),
                    ),
                )
            }
            return
        }
        addToCart(item, selected = emptySet(), quantity = 1)
    }

    private fun onOptionToggled(groupId: String, optionId: String) {
        updateState {
            val sheet = sheet ?: return@updateState this
            val group = sheet.item.optionGroups.firstOrNull { it.id == groupId }
                ?: return@updateState this
            copy(
                sheet = sheet.copy(
                    selectedOptionIds = MenuOptionRules.toggle(group, sheet.selectedOptionIds, optionId),
                ),
            )
        }
    }

    private fun onAddToCart() {
        val sheet = currentState.sheet ?: return
        if (!sheet.canAdd) {
            // Ошибки показываем только после попытки: краснеть на пустой,
            // ещё не заполненной форме — значит ругаться авансом.
            updateState { copy(sheet = sheet.copy(validationShown = true)) }
            return
        }
        addToCart(sheet.item, sheet.selectedOptionIds, sheet.quantity)
        updateState { copy(sheet = null) }
    }

    private fun addToCart(item: MenuItem, selected: Set<String>, quantity: Int) {
        val options = MenuOptionRules.chosenOptions(item, selected)
        val line = CartLine(
            id = CartCalculator.lineId(item.id, selected),
            itemId = item.id,
            name = item.name,
            unitPriceSum = MenuOptionRules.price(item, selected),
            quantity = quantity,
            optionIds = selected,
            optionsLabel = options.joinToString(OPTIONS_SEPARATOR, transform = MenuOption::name),
        )

        viewModelScope.launch {
            val activePlaceId = cartRepository.activePlaceId()
            if (activePlaceId != null && activePlaceId != placeId) {
                pendingLine = line
                val otherPlaceName = cartRepository.snapshot(activePlaceId).placeName
                updateState { copy(conflictPlaceName = otherPlaceName) }
                return@launch
            }
            store(line)
        }
    }

    private fun onConflictConfirmed() {
        val line = pendingLine ?: return
        pendingLine = null
        updateState { copy(conflictPlaceName = null) }
        viewModelScope.launch {
            cartRepository.clearAll()
            store(line)
        }
    }

    private suspend fun store(line: CartLine) {
        cartRepository.add(
            placeId = placeId,
            placeName = currentState.placeName,
            line = line,
        )
        emitEffect(MenuEffect.ItemAdded(line.name))
    }

    private companion object {
        const val OPTIONS_SEPARATOR = ", "
    }
}
