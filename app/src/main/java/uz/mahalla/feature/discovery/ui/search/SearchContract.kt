package uz.mahalla.feature.discovery.ui.search

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceSort

/**
 * Состояние поиска (эпик 4.3).
 *
 * [DiscoveryFilters.query] — источник истины для запроса; отдельного поля в
 * состоянии нет, чтобы строка поиска и фильтр не могли разъехаться.
 */
data class SearchState(
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val results: ScreenState<List<Place>> = ScreenState.Loading,
    val history: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Догрузка страницы провалилась — дальше только по кнопке «повторить». */
    val loadMoreFailed: Boolean = false,
    val fromCache: Boolean = false,
    val filtersVisible: Boolean = false,
) : UiState {

    val query: String get() = filters.query

    /**
     * История заменяет выдачу, пока пользователь ничего не искал: показывать
     * «ничего не найдено» до первого запроса нечестно.
     *
     * Активный фильтр — уже поиск, даже с пустой строкой: с главной по плитке
     * категории сюда приходят как раз так, и подменять готовую выдачу списком
     * прошлых запросов нельзя.
     */
    val showHistory: Boolean
        get() = filters.query.isBlank() && filters.activeCount == 0 && history.isNotEmpty()

    val activeFilterCount: Int get() = filters.activeCount
}

sealed interface SearchEvent : UiEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object QuerySubmitted : SearchEvent
    data object QueryCleared : SearchEvent

    data class HistoryClicked(val query: String) : SearchEvent
    data class HistoryRemoved(val query: String) : SearchEvent
    data object HistoryCleared : SearchEvent

    data object FiltersOpened : SearchEvent
    data object FiltersClosed : SearchEvent
    data class CategoryToggled(val category: PlaceCategory) : SearchEvent
    data class DistanceSelected(val meters: Int?) : SearchEvent
    data class RatingSelected(val rating: Double?) : SearchEvent
    data object OpenNowToggled : SearchEvent
    data class SortSelected(val sort: PlaceSort) : SearchEvent
    data object FiltersReset : SearchEvent

    data object LoadMore : SearchEvent
    data object Retry : SearchEvent
    data class PlaceClicked(val placeId: String) : SearchEvent
}

sealed interface SearchEffect : UiEffect {
    data class OpenPlace(val placeId: String) : SearchEffect
}
