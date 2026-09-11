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
import uz.mahalla.feature.place.domain.PlaceEditDraft
import uz.mahalla.feature.place.domain.Review
import uz.mahalla.feature.place.domain.ReviewDraft
import uz.mahalla.feature.promotions.domain.Promotion
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
    /** Кто вошёл. Нужен, чтобы отличить свой отзыв от чужого (issue #76). */
    val userId: String? = null,
    /** `null` — форма отзыва закрыта. */
    val reviewForm: ReviewFormState? = null,
    /** Отзыв, для которого спрошено подтверждение удаления. */
    val reviewPendingDelete: Review? = null,
    val deletingReview: Boolean = false,
    /** Отказ на удалении — показывается в блоке отзывов текстом сервера. */
    val reviewDeleteFailure: ApiFailure? = null,
    /**
     * Акции заведения (issue #104). Пустой список — секции нет: отказ этой
     * ручки карточку не роняет, а заголовок над пустотой обещает то, чего нет.
     */
    val promotions: List<Promotion> = emptyList(),
    /** `null` — форма правки места закрыта (issue #188). */
    val editForm: PlaceEditFormState? = null,
    /** `null` — форма ответа на отзыв закрыта (issue #188). */
    val reviewReplyForm: ReviewReplyFormState? = null,
) : UiState {

    val data: PlaceDetails? get() = (details as? ScreenState.Content)?.data

    /**
     * Владелец заведения (issue #188): `ownerId` карточки совпал с id
     * вошедшего аккаунта. Проверка клиентская и ничего не гарантирует — если
     * ошиблись, бэкенд всё равно ответит отказом на правку или ответ на
     * отзыв; смысл в том, чтобы не показывать кнопку, которая точно откажет.
     */
    val isOwner: Boolean
        get() = userId?.takeIf(String::isNotBlank)?.let { id -> data?.ownerId == id } == true

    /** Правка предлагается только на живой карточке — как и добавление отзыва. */
    val canEditPlace: Boolean
        get() = isOwner && data?.let { !it.fromCache } == true

    /** В свёрнутом виде показываем только первые отзывы — остальное по кнопке. */
    val visibleReviews get() = data?.reviews.orEmpty().let {
        if (allReviewsShown) it else it.take(PREVIEW_REVIEWS)
    }

    val hasHiddenReviews: Boolean
        get() = !allReviewsShown && data?.reviews.orEmpty().size > PREVIEW_REVIEWS

    /**
     * Свой отзыв — тот, у которого автор совпал с вошедшим. Пустой [userId] или
     * отзыв без автора «своим» не считается: показать чужому человеку кнопку
     * удаления хуже, чем не показать её владельцу.
     */
    val myReview: Review?
        get() = userId?.takeIf(String::isNotBlank)?.let { id ->
            data?.reviews?.firstOrNull { it.authorId == id }
        }

    fun isMine(review: Review): Boolean = myReview?.id == review.id

    /**
     * Форма предлагается только на живой карточке: у отзыва из кэша нет ни
     * подтверждения, что место существует, ни свежего списка, в котором виден
     * уже оставленный отзыв. Второй отзыв не предлагаем — бэкенд его отклонит,
     * и правильнее показать свой с кнопкой удаления.
     */
    val canAddReview: Boolean
        get() = data?.let { !it.fromCache } == true && myReview == null

    companion object {
        const val PREVIEW_REVIEWS = 3
    }
}

/**
 * Форма отзыва. [failure] — ответ сервера (issue #34): в шторке он и остаётся,
 * иначе человек закроет её вместе с объяснением, почему отзыв не ушёл.
 */
data class ReviewFormState(
    val draft: ReviewDraft = ReviewDraft(),
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = draft.canSubmit && !submitting
}

/** Форма правки карточки места (issue #188), тот же приём, что у [ReviewFormState]. */
data class PlaceEditFormState(
    val draft: PlaceEditDraft = PlaceEditDraft(),
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = draft.canSubmit && !submitting
}

/**
 * Форма ответа на отзыв (issue #188). [review] — на какой отзыв отвечаем;
 * поле предзаполняется уже существующим ответом, если он есть — иначе
 * повторное открытие формы стирало бы прежний текст молча.
 */
data class ReviewReplyFormState(
    val review: Review,
    val text: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val trimmedText: String get() = text.trim()
    val canSubmit: Boolean
        get() = trimmedText.isNotEmpty() && trimmedText.length <= MAX_LENGTH && !submitting

    companion object {
        const val MAX_LENGTH = 2000
    }
}

sealed interface PlaceDetailsEvent : UiEvent {
    data object Retry : PlaceDetailsEvent
    data object HoursToggled : PlaceDetailsEvent
    data object AllReviewsRequested : PlaceDetailsEvent
    data class ActionClicked(val action: PlaceAction) : PlaceDetailsEvent
    data object BackClicked : PlaceDetailsEvent

    // --- Отзыв (issue #76) ---
    data object AddReviewClicked : PlaceDetailsEvent
    data object ReviewFormDismissed : PlaceDetailsEvent
    data class ReviewRatingSelected(val rating: Int) : PlaceDetailsEvent
    data class ReviewTextChanged(val text: String) : PlaceDetailsEvent
    data object ReviewSubmitted : PlaceDetailsEvent
    data class ReviewDeleteRequested(val review: Review) : PlaceDetailsEvent
    data object ReviewDeleteConfirmed : PlaceDetailsEvent
    data object ReviewDeleteDismissed : PlaceDetailsEvent

    // --- Правка карточки места владельцем (issue #188) ---
    data object EditPlaceClicked : PlaceDetailsEvent
    data object EditFormDismissed : PlaceDetailsEvent
    data class EditNameChanged(val value: String) : PlaceDetailsEvent
    data class EditDescriptionChanged(val value: String) : PlaceDetailsEvent
    data class EditAddressChanged(val value: String) : PlaceDetailsEvent
    data class EditCityChanged(val value: String) : PlaceDetailsEvent
    data class EditPhoneChanged(val value: String) : PlaceDetailsEvent
    data class EditWebsiteChanged(val value: String) : PlaceDetailsEvent
    data object EditSubmitted : PlaceDetailsEvent

    // --- Ответ владельца на отзыв (issue #188) ---
    data class ReviewReplyClicked(val review: Review) : PlaceDetailsEvent
    data object ReviewReplyDismissed : PlaceDetailsEvent
    data class ReviewReplyTextChanged(val text: String) : PlaceDetailsEvent
    data object ReviewReplySubmitted : PlaceDetailsEvent
}

sealed interface PlaceDetailsEffect : UiEffect {
    data class Dial(val phone: String) : PlaceDetailsEffect
    data class OpenRoute(val point: GeoPoint, val label: String) : PlaceDetailsEffect

    /** Очередь, бронь и заказ — вертикали следующих эпиков. */
    data class OpenVertical(
        val action: PlaceAction,
        val placeId: String,
        val placeName: String,
    ) : PlaceDetailsEffect

    data object NavigateBack : PlaceDetailsEffect
}
