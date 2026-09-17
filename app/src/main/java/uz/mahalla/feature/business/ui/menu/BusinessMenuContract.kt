package uz.mahalla.feature.business.ui.menu

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.NewMenuItemError
import uz.mahalla.feature.business.domain.NewMenuItemForm

/**
 * Состояние меню и стоп-листа (задача 12.4).
 *
 * @param pendingItemId позиция, у которой сейчас переворачивается стоп-лист.
 * Точечная блокировка: остальные строки на это время тоже заперты — ручка
 * ничего не возвращает, и два переключения подряд разошлись бы с сервером.
 * @param formErrors замечания к форме новой позиции. Показываются **после
 * первой попытки сохранить**, а не по мере набора: подчёркивать красным поле,
 * которое человек ещё не дописал, — это ругаться на незаконченную мысль.
 */
data class BusinessMenuState(
    val placeName: String = "",
    val menu: ScreenState<BusinessMenu> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val pendingItemId: String? = null,
    val actionFailure: ApiFailure? = null,
    val isFormVisible: Boolean = false,
    val form: NewMenuItemForm = NewMenuItemForm(),
    val formErrors: List<NewMenuItemError> = emptyList(),
    val isSaving: Boolean = false,
    val formFailure: ApiFailure? = null,
) : UiState {

    val isBusy: Boolean get() = pendingItemId != null || isSaving

    /** Сколько позиций сейчас в стоп-листе — подпись в шапке. */
    val stoppedCount: Int get() = (menu as? ScreenState.Content)?.data?.stoppedCount ?: 0

    /**
     * Разделы, куда можно положить новую позицию. Пустой список означает, что
     * форму открывать бессмысленно: `CreateItemRequest` требует `menuId`, а
     * взять его неоткуда — раздел создаётся не из приложения.
     */
    val sectionIds: List<String>
        get() = (menu as? ScreenState.Content)?.data?.sections?.map { it.id }.orEmpty()

    fun hasError(error: NewMenuItemError): Boolean = error in formErrors
}

sealed interface BusinessMenuEvent : UiEvent {
    data object ScreenResumed : BusinessMenuEvent
    data object Refreshed : BusinessMenuEvent
    data object Retry : BusinessMenuEvent

    /** Стоп-лист: убрать позицию из продажи или вернуть её. */
    data class StopListToggled(val itemId: String) : BusinessMenuEvent

    data object AddItemClicked : BusinessMenuEvent
    data object FormDismissed : BusinessMenuEvent
    data class SectionSelected(val sectionId: String) : BusinessMenuEvent
    data class NameChanged(val value: String) : BusinessMenuEvent
    data class PriceChanged(val value: String) : BusinessMenuEvent
    data class DescriptionChanged(val value: String) : BusinessMenuEvent
    data class PrepMinutesChanged(val value: String) : BusinessMenuEvent
    data class HalalChanged(val value: Boolean) : BusinessMenuEvent
    data object SaveClicked : BusinessMenuEvent
}

sealed interface BusinessMenuEffect : UiEffect {
    /** Позиция добавлена — snackbar с её названием. */
    data class ItemCreated(val name: String) : BusinessMenuEffect

    /** Стоп-лист перевёрнут: [stopped] — позиция снята с продажи. */
    data class StopListChanged(val name: String, val stopped: Boolean) : BusinessMenuEffect
}
