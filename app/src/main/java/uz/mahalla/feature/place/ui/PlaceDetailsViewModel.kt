package uz.mahalla.feature.place.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.analytics.AnalyticsEvents
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.media.domain.MediaFile
import uz.mahalla.feature.place.domain.OpeningHoursCalculator
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.promotions.data.PromotionsRepository
import uz.mahalla.feature.promotions.domain.PromotionFeed
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
    private val promotions: PromotionsRepository,
    private val profileStore: UserProfileStore,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<PlaceDetailsState, PlaceDetailsEvent, PlaceDetailsEffect>(PlaceDetailsState()) {

    private val placeId: String = savedStateHandle.toRoute<PlaceRoute>().placeId

    init {
        load()
        loadPromotions()
        // `VIEW` — единственное событие, которое не ждёт ответа сервера:
        // карточку открыли, даже если её содержимое не приехало. Отправляется
        // один раз на создание ViewModel, а не на каждый `Retry`, иначе один
        // просмотр в панели превратится в несколько.
        analytics.track(AnalyticsEvents.placeViewed(placeId))
        viewModelScope.launch {
            // Свой отзыв узнаётся по id аккаунта, и профиль лежит локально —
            // отдельного `GET /users/me` у бэкенда нет (issue #61).
            val userId = profileStore.current().id
            updateState { copy(userId = userId) }
        }
    }

    override fun onEvent(event: PlaceDetailsEvent) {
        when (event) {
            PlaceDetailsEvent.Retry -> {
                load()
                loadPromotions()
            }

            PlaceDetailsEvent.HoursToggled -> updateState { copy(hoursExpanded = !hoursExpanded) }
            PlaceDetailsEvent.AllReviewsRequested -> updateState { copy(allReviewsShown = true) }
            PlaceDetailsEvent.BackClicked -> emitEffect(PlaceDetailsEffect.NavigateBack)
            is PlaceDetailsEvent.ActionClicked -> onAction(event.action)

            PlaceDetailsEvent.AddReviewClicked -> updateState {
                copy(reviewForm = ReviewFormState())
            }

            PlaceDetailsEvent.ReviewFormDismissed -> updateState { copy(reviewForm = null) }

            is PlaceDetailsEvent.ReviewRatingSelected -> updateForm {
                // Ошибка сервера относилась к прошлой попытке: править форму и
                // читать под ней старый отказ — противоречие.
                copy(draft = draft.withRating(event.rating), failure = null)
            }

            is PlaceDetailsEvent.ReviewTextChanged -> updateForm {
                copy(draft = draft.withText(event.text), failure = null)
            }

            PlaceDetailsEvent.ReviewSubmitted -> submitReview()

            is PlaceDetailsEvent.ReviewDeleteRequested -> updateState {
                copy(reviewPendingDelete = event.review, reviewDeleteFailure = null)
            }

            PlaceDetailsEvent.ReviewDeleteDismissed -> updateState {
                copy(reviewPendingDelete = null)
            }

            PlaceDetailsEvent.ReviewDeleteConfirmed -> deleteReview()

            is PlaceDetailsEvent.GalleryPhotoDeleteRequested -> updateState {
                copy(galleryDeletePending = event.photo, galleryDeleteFailure = null)
            }

            PlaceDetailsEvent.GalleryPhotoDeleteDismissed -> updateState {
                copy(galleryDeletePending = null)
            }

            PlaceDetailsEvent.GalleryPhotoDeleteConfirmed -> deleteGalleryPhoto()
        }
    }

    /**
     * @param silent не сбрасывать карточку в скелетон. После отправки отзыва
     * перезапрос идёт именно так: рейтинг считает сервер, но экран, который
     * человек только что читал, не должен мигать целиком, а провал обновления
     * не должен стирать уже показанные данные.
     */
    private fun load(silent: Boolean = false) {
        if (!silent) updateState { copy(details = ScreenState.Loading) }
        viewModelScope.launch {
            when (val result = repository.placeDetails(placeId)) {
                is ApiResult.Failure -> if (!silent) {
                    updateState { copy(details = ScreenState.Error(result.failure)) }
                }
                is ApiResult.Success -> updateState { withSchedule(result.data) }
            }
        }
    }

    /**
     * Акции заведения (issue #104) — отдельная ручка, и загружается она
     * параллельно карточке: последовательный запрос удвоил бы время до первого
     * экрана.
     *
     * Отказ прячет секцию, а не роняет карточку: ради акции сюда не приходили,
     * а экран ошибки поверх приехавшего заведения хуже отсутствующего блока.
     * Истёкшая акция не показывается — обещание скидки, которой уже нет, хуже
     * пустоты.
     */
    private fun loadPromotions() {
        viewModelScope.launch {
            val items = when (val result = promotions.placePromotions(placeId)) {
                is ApiResult.Failure -> emptyList()
                is ApiResult.Success -> PromotionFeed.live(result.data, clock.instant())
            }
            updateState { copy(promotions = items) }
        }
    }

    private fun submitReview() {
        val form = currentState.reviewForm ?: return
        if (!form.canSubmit) return

        updateState { copy(reviewForm = form.copy(submitting = true, failure = null)) }
        viewModelScope.launch {
            when (val result = repository.addReview(placeId, form.draft)) {
                is ApiResult.Failure -> updateState {
                    // Черновик остаётся в форме: набранный текст — работа
                    // человека, и терять её из-за отказа сервера нельзя.
                    copy(reviewForm = form.copy(submitting = false, failure = result.failure))
                }

                is ApiResult.Success -> {
                    updateState { copy(reviewForm = null) }
                    analytics.track(AnalyticsEvents.reviewSubmitted(placeId))
                    // Рейтинг места пересчитывает сервер: считать его на клиенте
                    // значит разойтись с выдачей на главной.
                    load(silent = true)
                }
            }
        }
    }

    private fun deleteReview() {
        val review = currentState.reviewPendingDelete ?: return

        updateState { copy(deletingReview = true, reviewDeleteFailure = null) }
        viewModelScope.launch {
            when (val result = repository.deleteReview(review.id)) {
                is ApiResult.Failure -> updateState {
                    copy(
                        deletingReview = false,
                        reviewPendingDelete = null,
                        reviewDeleteFailure = result.failure,
                    )
                }

                is ApiResult.Success -> {
                    updateState { copy(deletingReview = false, reviewPendingDelete = null) }
                    load(silent = true)
                }
            }
        }
    }

    /**
     * Удаление своего фото (issue #185) — **оптимистичное**: файл необратим, и
     * ждать ответа сервера, прежде чем убрать его из ленты, только удлиняет
     * то же самое ожидание для человека. Отказ возвращает фото на место и
     * показывает причину текстом сервера — молчаливого 403 быть не должно.
     */
    private fun deleteGalleryPhoto() {
        val photo = currentState.galleryDeletePending ?: return

        updateState {
            copy(galleryDeletePending = null, galleryDeleteFailure = null)
                .withPhotos(data?.photos.orEmpty() - photo)
        }
        viewModelScope.launch {
            when (val result = repository.deleteMediaFile(photo.id)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> updateState {
                    // Возвращаем именно удалённое фото в **текущий** список, а
                    // не переигрываем весь снимок «до удаления» целиком: пока
                    // запрос летел, карточка могла обновиться отдельным силент-
                    // перезапросом (issue #76), и грубый откат стёр бы его.
                    val photos = data?.photos.orEmpty()
                    val restored = if (photo in photos) photos else photos + photo
                    withPhotos(restored).copy(galleryDeleteFailure = result.failure)
                }
            }
        }
    }

    private fun PlaceDetailsState.withPhotos(photos: List<MediaFile>): PlaceDetailsState {
        val content = details as? ScreenState.Content<PlaceDetails> ?: return this
        return copy(details = content.copy(data = content.data.copy(photos = photos)))
    }

    private fun updateForm(transform: ReviewFormState.() -> ReviewFormState) {
        updateState { copy(reviewForm = reviewForm?.transform()) }
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
            // Событие уходит вместе с эффектом, а не вместо него: если
            // телефона или координат нет, действия не было — нажали по
            // кнопке, которой на экране быть не должно (`PlaceActions`).
            PlaceAction.Call -> details.contacts.phone
                ?.let {
                    analytics.track(AnalyticsEvents.placeCalled(placeId))
                    emitEffect(PlaceDetailsEffect.Dial(it))
                }

            PlaceAction.Route -> details.place.point
                ?.let {
                    analytics.track(AnalyticsEvents.routeRequested(placeId))
                    emitEffect(PlaceDetailsEffect.OpenRoute(it, details.place.name))
                }

            PlaceAction.Queue,
            PlaceAction.Booking,
            PlaceAction.Gaming,
            PlaceAction.Doctor,
            PlaceAction.Cinema,
            PlaceAction.Order,
            PlaceAction.Shop,
            PlaceAction.Products,
            ->
                emitEffect(
                    PlaceDetailsEffect.OpenVertical(action, placeId, details.place.name),
                )
        }
    }
}
