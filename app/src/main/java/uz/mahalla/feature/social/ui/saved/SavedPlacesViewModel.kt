package uz.mahalla.feature.social.ui.saved

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.social.data.SavedPlacesPage
import uz.mahalla.feature.social.data.SocialRepository
import javax.inject.Inject

/**
 * «Избранное» (issue #75).
 *
 * Список собирается репозиторием N+1 запросом — бэкенд отдаёт только
 * идентификаторы, — поэтому здесь всё как у обычной выдачи: страница,
 * догрузка, pull-to-refresh и перечит на возврате.
 */
@HiltViewModel
class SavedPlacesViewModel @Inject constructor(
    private val repository: SocialRepository,
) : MviViewModel<SavedPlacesState, SavedPlacesEvent, SavedPlacesEffect>(SavedPlacesState()) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: SavedPlacesEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на
            // уже сменившееся состояние.
            SavedPlacesEvent.ScreenResumed ->
                if (!currentState.places.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            SavedPlacesEvent.Refreshed -> load(showLoading = false, refreshing = true)
            SavedPlacesEvent.Retry -> load()
            SavedPlacesEvent.LoadMore -> loadMore()

            is SavedPlacesEvent.PlaceClicked ->
                emitEffect(SavedPlacesEffect.OpenPlace(event.placeId))

            SavedPlacesEvent.BackClicked -> emitEffect(SavedPlacesEffect.NavigateBack)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                places = if (showLoading) ScreenState.Loading else places,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            apply(repository.savedPlaces(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun apply(result: ApiResult<SavedPlacesPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(places = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    places = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Провал догрузки не стирает уже показанное, но и дёргать сеть в цикле
     * нельзя: список не вырос, автотриггер по концу больше не сработает —
     * поэтому хвост переходит в «повторить» вместе с причиной отказа.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.places as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.savedPlaces(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            places = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Место может приехать на двух соседних страницах, если список пополнили
     * между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(current: List<Place>, next: List<Place>): List<Place> {
        val known = current.mapTo(mutableSetOf(), Place::id)
        return current + next.filter { known.add(it.id) }
    }
}
