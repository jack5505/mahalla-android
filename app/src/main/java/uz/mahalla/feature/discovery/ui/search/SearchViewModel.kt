package uz.mahalla.feature.discovery.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.SearchHistoryStore
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.navigation.SearchRoute
import javax.inject.Inject

/**
 * Поиск и фильтры (эпик 4.3).
 *
 * Запрос уходит с задержкой [SEARCH_DEBOUNCE_MS]: без неё каждая буква —
 * отдельный сетевой вызов, а ответы приходят вразнобой и список моргает.
 * Смена фильтра, наоборот, ищет сразу — это осознанное действие, ждать его
 * незачем.
 *
 * В историю попадают только явно отправленные запросы (Enter или выбор из
 * истории): иначе там окажутся все промежуточные обрывки набора.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val historyStore: SearchHistoryStore,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<SearchState, SearchEvent, SearchEffect>(SearchState()) {

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        val route = savedStateHandle.toRoute<SearchRoute>()
        val category = route.categoryId?.let(PlaceCategory.Companion::fromApi)
        updateState {
            copy(
                filters = filters.copy(
                    query = route.query.orEmpty(),
                    // Other сюда попасть не может: в маршруте лежит apiValue
                    // выбранной категории, а у Other он пустой.
                    categories = setOfNotNull(category?.takeIf { it != PlaceCategory.Other }),
                ),
            )
        }

        viewModelScope.launch {
            historyStore.queries.collect { queries -> updateState { copy(history = queries) } }
        }
        search(delayMillis = 0)
    }

    override fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> {
                updateFilters { copy(query = event.query) }
                search(delayMillis = SEARCH_DEBOUNCE_MS)
            }

            SearchEvent.QuerySubmitted -> {
                rememberQuery(currentState.query)
                search(delayMillis = 0)
            }

            SearchEvent.QueryCleared -> {
                updateFilters { copy(query = "") }
                search(delayMillis = 0)
            }

            is SearchEvent.HistoryClicked -> {
                updateFilters { copy(query = event.query) }
                rememberQuery(event.query)
                search(delayMillis = 0)
            }

            is SearchEvent.HistoryRemoved -> viewModelScope.launch {
                historyStore.remove(event.query)
            }

            SearchEvent.HistoryCleared -> viewModelScope.launch { historyStore.clear() }

            SearchEvent.FiltersOpened -> updateState { copy(filtersVisible = true) }
            SearchEvent.FiltersClosed -> updateState { copy(filtersVisible = false) }

            is SearchEvent.CategoryToggled -> applyFilters { toggleCategory(event.category) }
            is SearchEvent.DistanceSelected -> applyFilters { copy(maxDistanceMeters = event.meters) }
            is SearchEvent.RatingSelected -> applyFilters { copy(minRating = event.rating) }
            SearchEvent.OpenNowToggled -> applyFilters { copy(openNowOnly = !openNowOnly) }
            is SearchEvent.SortSelected -> applyFilters { copy(sort = event.sort) }
            SearchEvent.FiltersReset -> applyFilters { cleared() }

            SearchEvent.LoadMore -> loadMore()
            SearchEvent.Retry -> search(delayMillis = 0)
            is SearchEvent.PlaceClicked -> emitEffect(SearchEffect.OpenPlace(event.placeId))
        }
    }

    private fun applyFilters(transform: DiscoveryFilters.() -> DiscoveryFilters) {
        updateFilters(transform)
        search(delayMillis = 0)
    }

    private fun updateFilters(transform: DiscoveryFilters.() -> DiscoveryFilters) {
        updateState { copy(filters = filters.transform()) }
    }

    private fun rememberQuery(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch { historyStore.add(query) }
    }

    /**
     * Новый поиск всегда отменяет предыдущий: иначе ответ на «пицц» способен
     * прийти после ответа на «пицца» и перезаписать более точный результат.
     */
    private fun search(delayMillis: Long) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        loadedPage = 0
        val filters = currentState.filters
        searchJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            updateState { copy(results = ScreenState.Loading, isLoadingMore = false) }
            when (val result = repository.places(filters, page = 0)) {
                is ApiResult.Failure -> updateState {
                    copy(results = ScreenState.Error(result.error), hasMore = false, fromCache = false)
                }

                is ApiResult.Success -> {
                    loadedPage = result.data.page
                    updateState {
                        copy(
                            results = if (result.data.items.isEmpty()) {
                                ScreenState.Empty
                            } else {
                                ScreenState.Content(result.data.items)
                            },
                            hasMore = result.data.hasMore,
                            fromCache = result.data.fromCache,
                        )
                    }
                }
            }
        }
    }

    /**
     * Догрузка страницы. Ошибка здесь не стирает уже показанный список —
     * потерять сотню карточек из-за одного неудачного запроса хуже, чем не
     * получить следующую двадцатку.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.results as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        val filters = state.filters
        updateState { copy(isLoadingMore = true) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.places(filters, page = nextPage)) {
                is ApiResult.Failure -> updateState { copy(isLoadingMore = false) }

                is ApiResult.Success -> {
                    loadedPage = result.data.page
                    updateState {
                        copy(
                            results = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Одно и то же место приезжает на двух соседних страницах, если выдача
     * пересортировалась между запросами. В LazyColumn это дубликат ключа и
     * падение, поэтому дедупликация по id обязательна.
     */
    private fun appended(current: List<Place>, next: List<Place>): List<Place> {
        val known = current.mapTo(mutableSetOf(), Place::id)
        return current + next.filter { known.add(it.id) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
