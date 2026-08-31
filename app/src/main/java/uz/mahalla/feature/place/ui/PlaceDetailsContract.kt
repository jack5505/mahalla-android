package uz.mahalla.feature.place.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import java.time.DayOfWeek

/**
 * Состояние карточки места (эпик 4.4).
 *
 * [openNow] считается локально по расписанию и потому может расходиться с
 * флагом из выдачи: карточка живёт на экране минутами, и статус на ней должен
 * стареть вместе со временем, а не с ответом сервера.
 */
data class PlaceDetailsState(
    val details: ScreenState<PlaceDetails> = ScreenState.Loading,
    val today: DayOfWeek? = null,
    val week: List<OpeningHours> = emptyList(),
    /** `null` — расписания нет, статус неизвестен. */
    val openNow: Boolean? = null,
    val hoursExpanded: Boolean = false,
    val allReviewsShown: Boolean = false,
) : UiState {

    val data: PlaceDetails? get() = (details as? ScreenState.Content)?.data

    /** В свёрнутом виде показываем только первые отзывы — остальное по кнопке. */
    val visibleReviews get() = data?.reviews.orEmpty().let {
        if (allReviewsShown) it else it.take(PREVIEW_REVIEWS)
    }

    val hasHiddenReviews: Boolean
        get() = !allReviewsShown && data?.reviews.orEmpty().size > PREVIEW_REVIEWS

    companion object {
        const val PREVIEW_REVIEWS = 3
    }
}

sealed interface PlaceDetailsEvent : UiEvent {
    data object Retry : PlaceDetailsEvent
    data object HoursToggled : PlaceDetailsEvent
    data object AllReviewsRequested : PlaceDetailsEvent
    data class ActionClicked(val action: PlaceAction) : PlaceDetailsEvent
    data object BackClicked : PlaceDetailsEvent
}

sealed interface PlaceDetailsEffect : UiEffect {
    data class Dial(val phone: String) : PlaceDetailsEffect
    data class OpenRoute(val point: GeoPoint, val label: String) : PlaceDetailsEffect

    /**
     * Очередь (форма заказа услуги, issue #71) и заказ (меню, эпик 5); бронь
     * ждёт своего эпика.
     *
     * [placeName] едет вместе с id: имя нужно заголовку следующего экрана, а
     * читать состояние карточки из обработчика эффекта нельзя — он собран один
     * раз и видел бы данные на момент подписки.
     */
    data class OpenVertical(
        val action: PlaceAction,
        val placeId: String,
        val placeName: String,
    ) : PlaceDetailsEffect

    data object NavigateBack : PlaceDetailsEffect
}
