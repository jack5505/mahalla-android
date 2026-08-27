package uz.mahalla.feature.discovery.ui.home

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.PlacePage
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.HomeSections
import javax.inject.Inject

/**
 * Главная (эпик 4.1).
 *
 * Один запрос без фильтров, из которого собираются оба блока: «рядом» и
 * «рекомендации» — это разные срезы одной выдачи, и второй сетевой вызов ради
 * них означал бы вдвое больше трафика при том же содержимом.
 */
@HiltViewModel
class DiscoveryHomeViewModel @Inject constructor(
    private val repository: CatalogRepository,
) : MviViewModel<DiscoveryHomeState, DiscoveryHomeEvent, DiscoveryHomeEffect>(
    DiscoveryHomeState(),
) {

    /** Загрузка ровно одна: повторный retry не должен плодить гонку ответов. */
    private var loadJob: Job? = null

    init {
        load(refreshing = false)
    }

    override fun onEvent(event: DiscoveryHomeEvent) {
        when (event) {
            DiscoveryHomeEvent.Retry -> load(refreshing = false)
            DiscoveryHomeEvent.Refresh -> load(refreshing = true)
            is DiscoveryHomeEvent.CategoryClicked ->
                emitEffect(DiscoveryHomeEffect.OpenSearch(event.category))

            is DiscoveryHomeEvent.PlaceClicked ->
                emitEffect(DiscoveryHomeEffect.OpenPlace(event.placeId))

            DiscoveryHomeEvent.SearchClicked ->
                emitEffect(DiscoveryHomeEffect.OpenSearch(category = null))

            DiscoveryHomeEvent.MapClicked -> emitEffect(DiscoveryHomeEffect.OpenMap)
        }
    }

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        updateState {
            copy(
                isRefreshing = refreshing,
                // Скелетон показываем только когда показывать больше нечего.
                content = if (refreshing && content is ScreenState.Content) content else ScreenState.Loading,
            )
        }
        loadJob = viewModelScope.launch {
            val result = repository.places(DiscoveryFilters())
            updateState { copy(isRefreshing = false, content = result.toContent()) }
        }
    }

    private fun ApiResult<PlacePage>.toContent(): ScreenState<DiscoveryHomeContent> = when (this) {
        is ApiResult.Failure -> ScreenState.Error(failure)
        is ApiResult.Success -> if (data.items.isEmpty()) {
            ScreenState.Empty
        } else {
            ScreenState.Content(
                DiscoveryHomeContent(
                    nearby = HomeSections.nearby(data.items),
                    recommended = HomeSections.recommended(data.items),
                    fromCache = data.fromCache,
                ),
            )
        }
    }
}
