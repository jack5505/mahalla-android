package uz.mahalla.feature.pharmacy.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
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
) : UiState

sealed interface PharmacyEvent : UiEvent {
    data class QueryChanged(val query: String) : PharmacyEvent

    /** Кнопка «искать» на клавиатуре: запрос уходит без задержки. */
    data object QuerySubmitted : PharmacyEvent

    data object Refreshed : PharmacyEvent
    data object Retry : PharmacyEvent
    data object LoadMore : PharmacyEvent
}
