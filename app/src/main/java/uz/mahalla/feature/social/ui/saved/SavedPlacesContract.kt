package uz.mahalla.feature.social.ui.saved

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place

/**
 * Состояние «Избранного» (issue #75).
 *
 * @param isRefreshing pull-to-refresh поверх уже показанного списка: скелетон
 * при нём не нужен, иначе каждое обновление выглядит как открытие экрана.
 * @param loadMoreFailure догрузка страницы не удалась — вместе с причиной,
 * чтобы кнопка «повторить» не осталась без объяснения (issue #34).
 */
data class SavedPlacesState(
    val places: ScreenState<List<Place>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface SavedPlacesEvent : UiEvent {
    /**
     * Возврат на экран: место могли убрать из избранного на его карточке, и
     * список, показанный до ухода, врал бы.
     */
    data object ScreenResumed : SavedPlacesEvent

    data object Refreshed : SavedPlacesEvent
    data object Retry : SavedPlacesEvent
    data object LoadMore : SavedPlacesEvent
    data class PlaceClicked(val placeId: String) : SavedPlacesEvent
    data object BackClicked : SavedPlacesEvent
}

sealed interface SavedPlacesEffect : UiEffect {
    data class OpenPlace(val placeId: String) : SavedPlacesEffect
    data object NavigateBack : SavedPlacesEffect
}
