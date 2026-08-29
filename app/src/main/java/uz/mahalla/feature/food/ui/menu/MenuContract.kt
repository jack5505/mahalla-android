package uz.mahalla.feature.food.ui.menu

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.MenuOptionRules
import uz.mahalla.feature.food.domain.SelectionError

/**
 * Меню заведения (эпик 5.1).
 *
 * [sheet] — открытая шторка модификаторов; она часть состояния экрана, а не
 * отдельного ViewModel'а: выбор в ней должен пережить поворот экрана, а
 * `rememberSaveable` пришлось бы учить сериализовать доменные типы.
 */
data class MenuState(
    val menu: ScreenState<Menu> = ScreenState.Loading,
    val placeName: String = "",
    val selectedCategoryId: String? = null,
    val cartItemCount: Int = 0,
    val cartTotalSum: Long = 0,
    val sheet: OptionsSheetState? = null,
    /** Корзина начата в другом заведении — спрашиваем, очищать ли её. */
    val conflictPlaceName: String? = null,
) : UiState {

    val data: Menu? get() = (menu as? ScreenState.Content)?.data

    val categories get() = data?.categories.orEmpty()

    /** Показываемая категория: выбранная или первая, если выбор ещё не сделан. */
    val visibleCategory get() = categories.firstOrNull { it.id == selectedCategoryId }
        ?: categories.firstOrNull()

    val hasCart: Boolean get() = cartItemCount > 0
}

/**
 * Состояние шторки модификаторов. Цена и ошибки считаются здесь, а не в
 * composable: доступность кнопки «добавить» — это правило, и проверяется оно
 * тестом.
 */
data class OptionsSheetState(
    val item: MenuItem,
    val selectedOptionIds: Set<String> = emptySet(),
    val quantity: Int = 1,
    /** Показывать ли ошибки: до первого нажатия «добавить» краснеть незачем. */
    val validationShown: Boolean = false,
) {
    val unitPriceSum: Long get() = MenuOptionRules.price(item, selectedOptionIds)

    val totalSum: Long get() = unitPriceSum * quantity

    val errors: List<SelectionError> get() = MenuOptionRules.validate(item, selectedOptionIds)

    val canAdd: Boolean get() = errors.isEmpty()

    val visibleErrors: List<SelectionError> get() = if (validationShown) errors else emptyList()
}

sealed interface MenuEvent : UiEvent {
    data object Retry : MenuEvent
    data class CategorySelected(val categoryId: String) : MenuEvent
    data class ItemClicked(val itemId: String) : MenuEvent
    data object SheetDismissed : MenuEvent
    data class OptionToggled(val groupId: String, val optionId: String) : MenuEvent
    data class SheetQuantityChanged(val quantity: Int) : MenuEvent
    data object AddToCartClicked : MenuEvent
    data object ConflictConfirmed : MenuEvent
    data object ConflictDismissed : MenuEvent
    data object CartClicked : MenuEvent
    data object BackClicked : MenuEvent
}

sealed interface MenuEffect : UiEffect {
    data class OpenCart(val placeId: String) : MenuEffect
    data class ItemAdded(val name: String) : MenuEffect
    data object NavigateBack : MenuEffect
}
