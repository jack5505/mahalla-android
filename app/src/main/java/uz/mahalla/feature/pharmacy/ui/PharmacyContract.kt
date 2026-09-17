package uz.mahalla.feature.pharmacy.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct

/**
 * Состояние витрины аптеки (issue #100).
 *
 * @param query то, что человек набрал. Поиск идёт **на сервере** (`?query=`):
 * пагинация у ручки есть, и фильтрация приехавшего списка прятала бы
 * совпадения с непрогруженных страниц.
 * @param searchedQuery запрос, которому соответствует показанный список.
 * Нужен пустому состоянию: «ничего не нашлось по „aspirin“» и «в этой аптеке
 * пока нет товаров» — разные сообщения, и второе на месте первого выглядит
 * как поломка поиска.
 * @param loadMoreFailure отказ догрузки — отдельно от [products]: список уже
 * на экране, и прятать его из-за неудавшегося хвоста незачем (issue #53).
 * @param isOwner владелец или менеджер заведения (issue #252) — витриной
 * правит тот же круг людей, что переключает «открыто сейчас» в «Моих
 * заведениях», откуда сюда и попадают с этим флагом уже выставленным: у
 * товаров аптеки нет своего `ownerId`, чтобы проверить это на месте.
 */
data class PharmacyState(
    val placeName: String = "",
    val query: String = "",
    val searchedQuery: String = "",
    val products: ScreenState<List<PharmacyProduct>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val isOwner: Boolean = false,
    val createForm: NewProductFormState? = null,
    val stockForm: StockEditFormState? = null,
) : UiState

/** Форма нового товара (issue #252). */
data class NewProductFormState(
    val draft: NewPharmacyProductDraft = NewPharmacyProductDraft(),
    /** Ошибки полей показываются только после первой попытки сохранить —
     * форма стартует пустой, и незаполненное обязательное поле не должно
     * выглядеть ошибкой раньше, чем человек успел его тронуть. */
    val submitAttempted: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
)

/** Форма правки остатка одного товара (issue #252, `PUT products/{id}/stock`). */
data class StockEditFormState(
    val productId: String,
    val productName: String,
    val quantityText: String,
    val submitAttempted: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val parsedQuantity: Int? get() = quantityText.trim().toIntOrNull()?.takeIf { it >= 0 }
    val isQuantityValid: Boolean get() = parsedQuantity != null
}

sealed interface PharmacyEvent : UiEvent {
    data class QueryChanged(val query: String) : PharmacyEvent

    /** Кнопка «искать» на клавиатуре: запрос уходит без задержки. */
    data object QuerySubmitted : PharmacyEvent

    data object Refreshed : PharmacyEvent
    data object Retry : PharmacyEvent
    data object LoadMore : PharmacyEvent

    // Новый товар (issue #252) — доступно только владельцу/менеджеру.
    data object AddProductClicked : PharmacyEvent
    data object CreateFormDismissed : PharmacyEvent
    data class CreateNameChanged(val value: String) : PharmacyEvent
    data class CreateManufacturerChanged(val value: String) : PharmacyEvent
    data class CreateDosageFormChanged(val value: String) : PharmacyEvent
    data class CreateStrengthChanged(val value: String) : PharmacyEvent
    data class CreateDescriptionChanged(val value: String) : PharmacyEvent
    data class CreatePriceChanged(val value: String) : PharmacyEvent
    data class CreateStockChanged(val value: String) : PharmacyEvent
    data class CreatePrescriptionChanged(val value: Boolean) : PharmacyEvent
    data object CreateSubmitted : PharmacyEvent

    // Остаток существующего товара (issue #252).
    data class StockEditClicked(val product: PharmacyProduct) : PharmacyEvent
    data object StockFormDismissed : PharmacyEvent
    data class StockQuantityChanged(val value: String) : PharmacyEvent
    data object StockSubmitted : PharmacyEvent
}
