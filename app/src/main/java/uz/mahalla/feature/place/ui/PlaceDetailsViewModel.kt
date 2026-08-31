package uz.mahalla.feature.place.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.place.domain.OpeningHoursCalculator
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.social.data.SocialRepository
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.feature.social.domain.PlaceCommentPage
import uz.mahalla.navigation.PlaceRoute
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Карточка места (эпик 4.4) вместе с социальными действиями (issue #75):
 * лайк, «Избранное» и комментарии.
 *
 * Часы берутся через [Clock] из графа, а не через `LocalDateTime.now()`:
 * иначе «открыто сейчас» невозможно проверить тестом.
 *
 * Карточка, состояние кнопок и комментарии грузятся тремя независимыми
 * запросами: ни отзывов, ни лайков не должно хватать, чтобы спрятать место,
 * ради которого человек сюда пришёл.
 */
@HiltViewModel
class PlaceDetailsViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val socialRepository: SocialRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<PlaceDetailsState, PlaceDetailsEvent, PlaceDetailsEffect>(PlaceDetailsState()) {

    private val placeId: String = savedStateHandle.toRoute<PlaceRoute>().placeId

    private var loadMoreCommentsJob: Job? = null
    private var loadedCommentsPage = 0

    init {
        load()
        loadSocial()
        loadComments()
    }

    override fun onEvent(event: PlaceDetailsEvent) {
        when (event) {
            PlaceDetailsEvent.Retry -> load()
            PlaceDetailsEvent.HoursToggled -> updateState { copy(hoursExpanded = !hoursExpanded) }
            PlaceDetailsEvent.AllReviewsRequested -> updateState { copy(allReviewsShown = true) }
            PlaceDetailsEvent.BackClicked -> emitEffect(PlaceDetailsEffect.NavigateBack)
            is PlaceDetailsEvent.ActionClicked -> onAction(event.action)

            PlaceDetailsEvent.LikeClicked -> onLikeClicked()
            PlaceDetailsEvent.SaveClicked -> onSaveClicked()
            PlaceDetailsEvent.SocialRetry -> loadSocial()

            is PlaceDetailsEvent.CommentDraftChanged -> updateState {
                copy(commentDraft = event.text, commentFailure = null)
            }

            PlaceDetailsEvent.CommentSubmitted -> submitComment()
            PlaceDetailsEvent.CommentsRetry -> loadComments()
            PlaceDetailsEvent.MoreCommentsRequested -> loadMoreComments()

            is PlaceDetailsEvent.CommentDeleteRequested -> updateState {
                copy(confirmDeleteComment = event.comment)
            }

            PlaceDetailsEvent.CommentDeleteDismissed -> updateState {
                copy(confirmDeleteComment = null)
            }

            PlaceDetailsEvent.CommentDeleteConfirmed -> deleteComment()
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
                emitEffect(PlaceDetailsEffect.OpenVertical(action, placeId))
        }
    }

    // --- Лайк и «Избранное» (issue #75) ---

    private fun loadSocial() {
        updateState { copy(socialLoading = true, socialFailure = null) }
        viewModelScope.launch {
            when (val result = socialRepository.status(placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(socialLoading = false, socialFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(social = result.data, socialLoading = false)
                }
            }
        }
    }

    /**
     * Оптимистичное нажатие: состояние переворачивается сразу, а отказ
     * возвращает **то самое** значение, которое было до нажатия, — не
     * «обратный переворот». Иначе ответ, разошедшийся с фактом (сервер уже
     * знал о лайке с другого устройства), оставил бы кнопку в третьем,
     * выдуманном состоянии.
     *
     * Пока запрос в пути, повторные нажатия игнорируются: два переключателя
     * в полёте одновременно кончаются тем, что ответы приезжают в
     * произвольном порядке.
     */
    private fun onLikeClicked() {
        val previous = currentState.social ?: return
        if (currentState.likePending) return

        updateState { copy(social = previous.toggledLike(), likePending = true, socialFailure = null) }
        viewModelScope.launch {
            when (val result = socialRepository.toggleLike(placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(social = previous, likePending = false, socialFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        // Счётчик берём серверный, если он приехал: он знает и
                        // о чужих лайках, случившихся между запросами.
                        social = (social ?: previous)
                            .withLike(result.data.liked, result.data.likes),
                        likePending = false,
                    )
                }
            }
        }
    }

    private fun onSaveClicked() {
        val previous = currentState.social ?: return
        if (currentState.savePending) return

        updateState { copy(social = previous.toggledSave(), savePending = true, socialFailure = null) }
        viewModelScope.launch {
            when (val result = socialRepository.toggleSave(placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(social = previous, savePending = false, socialFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        social = (social ?: previous).withSaved(result.data),
                        savePending = false,
                    )
                }
            }
        }
    }

    // --- Комментарии ---

    private fun loadComments() {
        loadMoreCommentsJob?.cancel()
        loadedCommentsPage = 0
        updateState {
            copy(
                comments = ScreenState.Loading,
                loadingMoreComments = false,
                loadMoreCommentsFailure = null,
            )
        }
        viewModelScope.launch { applyComments(socialRepository.comments(placeId, page = 0)) }
    }

    private fun applyComments(result: ApiResult<PlaceCommentPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(comments = ScreenState.Error(result.failure), hasMoreComments = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    comments = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMoreComments = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Номер страницы считается локально: сервер, не вернувший `page`, отдаёт
     * дефолтный `0`, и «следующей» навсегда осталась бы первая (issue #53).
     */
    private fun loadMoreComments() {
        val state = currentState
        if (!state.hasMoreComments || state.loadingMoreComments) return
        val loaded = state.comments as? ScreenState.Content ?: return
        if (loadMoreCommentsJob?.isActive == true) return

        val nextPage = loadedCommentsPage + 1
        updateState { copy(loadingMoreComments = true, loadMoreCommentsFailure = null) }
        loadMoreCommentsJob = viewModelScope.launch {
            when (val result = socialRepository.comments(placeId, page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(loadingMoreComments = false, loadMoreCommentsFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedCommentsPage = nextPage
                    updateState {
                        copy(
                            comments = ScreenState.Content(
                                appended(loaded.data, result.data.items),
                            ),
                            hasMoreComments = result.data.hasMore,
                            loadingMoreComments = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Комментарий может приехать на двух соседних страницах, если ленту
     * пополнили между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(
        current: List<PlaceComment>,
        next: List<PlaceComment>,
    ): List<PlaceComment> {
        val known = current.mapTo(mutableSetOf(), PlaceComment::id)
        return current + next.filter { known.add(it.id) }
    }

    private fun submitComment() {
        val state = currentState
        if (!state.canSubmitComment) return

        updateState { copy(sendingComment = true, commentFailure = null) }
        viewModelScope.launch {
            when (val result = socialRepository.addComment(placeId, state.commentDraft)) {
                is ApiResult.Failure -> updateState {
                    copy(sendingComment = false, commentFailure = result.failure)
                }

                // Отправленный комментарий встаёт первым и черновик очищается
                // только здесь: пока сервер не принял текст, стирать его из
                // поля значит потерять написанное.
                is ApiResult.Success -> updateState {
                    copy(
                        commentDraft = "",
                        sendingComment = false,
                        comments = ScreenState.Content(
                            appended(listOf(result.data), commentsOrEmpty()),
                        ),
                    )
                }
            }
        }
    }

    private fun deleteComment() {
        val comment = currentState.confirmDeleteComment ?: return
        updateState { copy(confirmDeleteComment = null, deletingCommentId = comment.id) }
        viewModelScope.launch {
            when (val result = socialRepository.deleteComment(comment.id)) {
                is ApiResult.Failure -> updateState {
                    copy(deletingCommentId = null, commentFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    val left = commentsOrEmpty().filterNot { it.id == comment.id }
                    copy(
                        deletingCommentId = null,
                        comments = if (left.isEmpty()) {
                            ScreenState.Empty
                        } else {
                            ScreenState.Content(left)
                        },
                    )
                }
            }
        }
    }

    private fun PlaceDetailsState.commentsOrEmpty(): List<PlaceComment> =
        (comments as? ScreenState.Content)?.data.orEmpty()
}
