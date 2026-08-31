package uz.mahalla.feature.place.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.place.domain.OpeningHoursCalculator
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.navigation.PlaceRoute
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Карточка места (эпик 4.4).
 *
 * Часы берутся через [Clock] из графа, а не через `LocalDateTime.now()`:
 * иначе «открыто сейчас» невозможно проверить тестом.
 */
@HiltViewModel
class PlaceDetailsViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<PlaceDetailsState, PlaceDetailsEvent, PlaceDetailsEffect>(PlaceDetailsState()) {

    private val placeId: String = savedStateHandle.toRoute<PlaceRoute>().placeId

    init {
        load()
    }

    override fun onEvent(event: PlaceDetailsEvent) {
        when (event) {
            PlaceDetailsEvent.Retry -> load()
            PlaceDetailsEvent.HoursToggled -> updateState { copy(hoursExpanded = !hoursExpanded) }
            PlaceDetailsEvent.AllReviewsRequested -> updateState { copy(allReviewsShown = true) }
            PlaceDetailsEvent.BackClicked -> emitEffect(PlaceDetailsEffect.NavigateBack)
            is PlaceDetailsEvent.ActionClicked -> onAction(event.action)
        }
    }

    private fun load() {
        updateState { copy(details = ScreenState.Loading) }
        viewModelScope.launch {
            when (val result = repository.placeDetails(placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(details = ScreenState.Error(result.failure))
                }
                is ApiResult.Success -> updateState { withSchedule(result.data) }
            }
        }
    }

    private fun PlaceDetailsState.withSchedule(details: PlaceDetails): PlaceDetailsState {
        val now = LocalDateTime.now(clock.withZone(DateTimeFormatters.AppZone))
        return copy(
            details = ScreenState.Content(details),
            today = now.dayOfWeek,
            week = if (details.hours.isEmpty()) {
                emptyList()
            } else {
                OpeningHoursCalculator.weekSchedule(details.hours)
            },
            // Расписания нет — статус не выдумываем: карточка из кэша иначе
            // объявила бы место закрытым просто потому, что часы не сохраняются.
            openNow = OpeningHoursCalculator.isOpenAt(details.hours, now)
                ?: details.place.isOpenNow.takeIf { !details.fromCache },
        )
    }

    private fun onAction(action: PlaceAction) {
        val details = currentState.data ?: return
        when (action) {
            PlaceAction.Call -> details.contacts.phone
                ?.let { emitEffect(PlaceDetailsEffect.Dial(it)) }

            PlaceAction.Route -> details.place.point
                ?.let { emitEffect(PlaceDetailsEffect.OpenRoute(it, details.place.name)) }

            PlaceAction.Queue, PlaceAction.Booking, PlaceAction.Order ->
                emitEffect(
                    PlaceDetailsEffect.OpenVertical(
                        action = action,
                        placeId = placeId,
                        placeName = details.place.name,
                    ),
                )
        }
    }
}
