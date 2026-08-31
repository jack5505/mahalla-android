package uz.mahalla.feature.place.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.social.domain.CommentRules
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.feature.social.domain.PlaceSocialStatus
import java.time.DayOfWeek

/**
 * Состояние карточки места (эпик 4.4 + социальные действия, issue #75).
 *
 * [openNow] считается локально по расписанию и потому может расходиться с
 * флагом из выдачи: карточка живёт на экране минутами, и статус на ней должен
 * стареть вместе со временем, а не с ответом сервера.
 *
 * @param social лайк и «Избранное». `null` — состояние кнопок неизвестно
 * (запрос не дошёл): рисовать «не нравится» вместо ответа сервера нельзя —
 * человек нажмёт и снимет собственный лайк, думая, что ставит его.
 * @param socialFailure отказ последнего действия (или самого запроса
 * состояния) вместе с ответом сервера — issue #34.
 * @param commentDraft черновик живёт в состоянии, а не в поле ввода: иначе
 * набранный текст исчезал бы при смене конфигурации.
 */
data class PlaceDetailsState(
    val details: ScreenState<PlaceDetails> = ScreenState.Loading,
    val today: DayOfWeek? = null,
    val week: List<OpeningHours> = emptyList(),
    /** `null` — расписания нет, статус неизвестен. */
    val openNow: Boolean? = null,
    val hoursExpanded: Boolean = false,
    val allReviewsShown: Boolean = false,
    val social: PlaceSocialStatus? = null,
    val socialLoading: Boolean = true,
    val socialFailure: ApiFailure? = null,
    val likePending: Boolean = false,
    val savePending: Boolean = false,
    val comments: ScreenState<List<PlaceComment>> = ScreenState.Loading,
    val hasMoreComments: Boolean = false,
    val loadingMoreComments: Boolean = false,
    val loadMoreCommentsFailure: ApiFailure? = null,
    val commentDraft: String = "",
    val sendingComment: Boolean = false,
    val commentFailure: ApiFailure? = null,
    val confirmDeleteComment: PlaceComment? = null,
    val deletingCommentId: String? = null,
) : UiState {

    val data: PlaceDetails? get() = (details as? ScreenState.Content)?.data

    /** В свёрнутом виде показываем только первые отзывы — остальное по кнопке. */
    val visibleReviews get() = data?.reviews.orEmpty().let {
        if (allReviewsShown) it else it.take(PREVIEW_REVIEWS)
    }

    val hasHiddenReviews: Boolean
        get() = !allReviewsShown && data?.reviews.orEmpty().size > PREVIEW_REVIEWS

    /** Пустой комментарий отправлять некуда, второй раз подряд — тоже. */
    val canSubmitComment: Boolean
        get() = !sendingComment && CommentRules.canSubmit(commentDraft)

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

    // --- Социальные действия (issue #75) ---

    data object LikeClicked : PlaceDetailsEvent
    data object SaveClicked : PlaceDetailsEvent

    /** Повтор запроса состояния кнопок: без него отказ выключал бы их до ухода с экрана. */
    data object SocialRetry : PlaceDetailsEvent

    data class CommentDraftChanged(val text: String) : PlaceDetailsEvent
    data object CommentSubmitted : PlaceDetailsEvent
    data object CommentsRetry : PlaceDetailsEvent
    data object MoreCommentsRequested : PlaceDetailsEvent
    data class CommentDeleteRequested(val comment: PlaceComment) : PlaceDetailsEvent
    data object CommentDeleteConfirmed : PlaceDetailsEvent
    data object CommentDeleteDismissed : PlaceDetailsEvent
}

sealed interface PlaceDetailsEffect : UiEffect {
    data class Dial(val phone: String) : PlaceDetailsEffect
    data class OpenRoute(val point: GeoPoint, val label: String) : PlaceDetailsEffect

    /** Очередь, бронь и заказ — вертикали следующих эпиков. */
    data class OpenVertical(val action: PlaceAction, val placeId: String) : PlaceDetailsEffect

    data object NavigateBack : PlaceDetailsEffect
}
