package uz.mahalla.feature.place.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceCapabilities
import uz.mahalla.feature.place.domain.PlaceContacts
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import uz.mahalla.feature.social.data.LikeResult
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.feature.social.domain.PlaceCommentPage
import uz.mahalla.feature.social.domain.PlaceSocialStatus
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakeSocialRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Карточка места (эпик 4.4).
 *
 * Часы приходят из подменяемого [Clock] — иначе «открыто сейчас» проверялось
 * бы только в рабочее время реального дня.
 *
 * Robolectric нужен из-за `SavedStateHandle.toRoute()`: разбор типизированного
 * маршрута идёт через настоящий `Bundle`, а в обычном JVM-тесте android.jar
 * заглушен (`isReturnDefaultValues = true`) и `placeId` читался бы как `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailsViewModelTest {

    // Загрузка карточки без таймеров — выполняется на месте.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeCatalogRepository()
    private val social = FakeSocialRepository()

    @Test
    fun `card is loaded for the id from the route`() = runTest {
        repository.details = ApiResult.Success(details())

        val state = viewModel().state.value

        assertEquals("Osh markazi", (state.details as ScreenState.Content).data.place.name)
    }

    @Test
    fun `open now is recomputed from the schedule, not taken from the list`() = runTest {
        // Карточка живёт на экране минутами и открывается из кэша: флаг из
        // выдачи к этому моменту уже устаревает.
        repository.details = ApiResult.Success(
            details(
                isOpenNow = false,
                hours = listOf(workingDay(DayOfWeek.MONDAY)),
            ),
        )

        val state = viewModel(clock = mondayAt("12:00")).state.value

        assertTrue(state.openNow!!)
    }

    @Test
    fun `outside working hours the card says closed`() = runTest {
        repository.details = ApiResult.Success(
            details(isOpenNow = true, hours = listOf(workingDay(DayOfWeek.MONDAY))),
        )

        val state = viewModel(clock = mondayAt("22:00")).state.value

        assertFalse(state.openNow!!)
    }

    @Test
    fun `without a schedule the flag from the list is used`() = runTest {
        repository.details = ApiResult.Success(details(isOpenNow = true, hours = emptyList()))

        assertTrue(viewModel().state.value.openNow!!)
    }

    @Test
    fun `a cached card does not claim a status it cannot know`() = runTest {
        // Расписание в кэш не попадает, поэтому «закрыто» здесь означало бы
        // выдумку, а не факт.
        repository.details = ApiResult.Success(
            details(isOpenNow = false, hours = emptyList()).copy(fromCache = true),
        )

        assertNull(viewModel().state.value.openNow)
    }

    @Test
    fun `week schedule always has seven rows`() = runTest {
        repository.details = ApiResult.Success(
            details(hours = listOf(workingDay(DayOfWeek.WEDNESDAY))),
        )

        assertEquals(7, viewModel().state.value.week.size)
    }

    @Test
    fun `no schedule means no schedule block`() = runTest {
        repository.details = ApiResult.Success(details(hours = emptyList()))

        assertTrue(viewModel().state.value.week.isEmpty())
    }

    @Test
    fun `error state offers a retry that works`() = runTest {
        repository.details = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        assertEquals(ScreenState.Error(ApiError.NoConnection), viewModel.state.value.details)

        repository.details = ApiResult.Success(details())
        viewModel.onEvent(PlaceDetailsEvent.Retry)

        assertTrue(viewModel.state.value.details is ScreenState.Content)
    }

    @Test
    fun `call action dials the stored number`() = runTest {
        repository.details = ApiResult.Success(details(phone = "+998901234567"))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.ActionClicked(PlaceAction.Call))

        assertEquals(PlaceDetailsEffect.Dial("+998901234567"), viewModel.effects.first())
    }

    @Test
    fun `route action carries the coordinates and the name`() = runTest {
        val point = GeoPoint(41.31, 69.28)
        repository.details = ApiResult.Success(details(point = point))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.ActionClicked(PlaceAction.Route))

        assertEquals(PlaceDetailsEffect.OpenRoute(point, "Osh markazi"), viewModel.effects.first())
    }

    @Test
    fun `queue action hands over to the vertical`() = runTest {
        repository.details = ApiResult.Success(
            details(capabilities = PlaceCapabilities(queue = true)),
        )
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.ActionClicked(PlaceAction.Queue))

        assertEquals(
            PlaceDetailsEffect.OpenVertical(PlaceAction.Queue, "p-1"),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `reviews are cut until the user asks for all of them`() = runTest {
        val many = (1..5).map { review("r$it") }
        repository.details = ApiResult.Success(details(reviews = many))
        val viewModel = viewModel()

        assertEquals(PlaceDetailsState.PREVIEW_REVIEWS, viewModel.state.value.visibleReviews.size)
        assertTrue(viewModel.state.value.hasHiddenReviews)

        viewModel.onEvent(PlaceDetailsEvent.AllReviewsRequested)

        assertEquals(5, viewModel.state.value.visibleReviews.size)
        assertFalse(viewModel.state.value.hasHiddenReviews)
    }

    @Test
    fun `a short review list has nothing hidden`() = runTest {
        repository.details = ApiResult.Success(details(reviews = listOf(review("r1"))))

        assertFalse(viewModel().state.value.hasHiddenReviews)
    }

    @Test
    fun `hours block folds and unfolds`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        assertFalse(viewModel.state.value.hoursExpanded)
        viewModel.onEvent(PlaceDetailsEvent.HoursToggled)
        assertTrue(viewModel.state.value.hoursExpanded)
    }

    @Test
    fun `back is an effect, not a direct navigation call`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.BackClicked)

        assertEquals(PlaceDetailsEffect.NavigateBack, viewModel.effects.first())
    }

    // --- Лайк, «Избранное» и комментарии (issue #75) ---

    @Test
    fun `like and save states come from the server, not from defaults`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(liked = true, saved = true, likes = 42))

        val state = viewModel().state.value

        assertEquals(PlaceSocialStatus(liked = true, saved = true, likes = 42), state.social)
        assertFalse(state.socialLoading)
    }

    @Test
    fun `an unknown like state is not drawn as not liked`() = runTest {
        // Иначе человек нажмёт и снимет собственный лайк, думая, что ставит его.
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Failure(ApiError.NoConnection)

        val state = viewModel().state.value

        assertNull(state.social)
        assertEquals(ApiError.NoConnection, state.socialFailure?.error)
    }

    @Test
    fun `a tap on the heart is applied before the server answers`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(liked = false, likes = 10))
        social.likeResult = ApiResult.Success(LikeResult(liked = true, likes = 11))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.LikeClicked)

        assertEquals(PlaceSocialStatus(liked = true, likes = 11), viewModel.state.value.social)
        assertEquals(listOf(PLACE_ID), social.likeCalls)
    }

    @Test
    fun `a failed like rolls back to the state before the tap`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(liked = false, likes = 10))
        social.likeResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.LikeClicked)

        assertEquals(PlaceSocialStatus(liked = false, likes = 10), viewModel.state.value.social)
        // Кнопка, вернувшаяся в прежнее состояние без объяснения, читается как
        // сломанная (issue #34).
        assertEquals(ApiError.NoConnection, viewModel.state.value.socialFailure?.error)
        assertFalse(viewModel.state.value.likePending)
    }

    @Test
    fun `the server counter replaces the optimistic one`() = runTest {
        // Между нажатием и ответом место могли лайкнуть ещё десять человек.
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(liked = false, likes = 10))
        social.likeResult = ApiResult.Success(LikeResult(liked = true, likes = 21))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.LikeClicked)

        assertEquals(21L, viewModel.state.value.social?.likes)
    }

    @Test
    fun `saving works the same way and rolls back too`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(saved = false, likes = 3))
        social.saveResult = ApiResult.Failure(ApiError.Forbidden)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.SaveClicked)

        assertFalse(viewModel.state.value.social!!.saved)
        assertEquals(ApiError.Forbidden, viewModel.state.value.socialFailure?.error)
    }

    @Test
    fun `save keeps the like counter untouched`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Success(PlaceSocialStatus(liked = true, likes = 7))
        social.saveResult = ApiResult.Success(true)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.SaveClicked)

        assertEquals(
            PlaceSocialStatus(liked = true, saved = true, likes = 7),
            viewModel.state.value.social,
        )
    }

    @Test
    fun `the like state can be retried without leaving the screen`() = runTest {
        repository.details = ApiResult.Success(details())
        social.status = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()

        social.status = ApiResult.Success(PlaceSocialStatus(liked = true, likes = 1))
        viewModel.onEvent(PlaceDetailsEvent.SocialRetry)

        assertEquals(1L, viewModel.state.value.social?.likes)
        assertNull(viewModel.state.value.socialFailure)
    }

    @Test
    fun `comments are loaded next to the card`() = runTest {
        repository.details = ApiResult.Success(details())
        social.commentPages[0] = ApiResult.Success(
            PlaceCommentPage(items = listOf(comment("c-1")), hasMore = true),
        )

        val state = viewModel().state.value

        assertEquals(listOf("c-1"), (state.comments as ScreenState.Content).data.map { it.id })
        assertTrue(state.hasMoreComments)
    }

    @Test
    fun `a comment failure does not hide the card`() = runTest {
        repository.details = ApiResult.Success(details())
        social.commentPages[0] = ApiResult.Failure(ApiError.NoConnection)

        val state = viewModel().state.value

        assertTrue(state.details is ScreenState.Content)
        assertTrue(state.comments is ScreenState.Error)
    }

    @Test
    fun `an empty comment cannot be sent`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDraftChanged("   "))
        assertFalse(viewModel.state.value.canSubmitComment)
        viewModel.onEvent(PlaceDetailsEvent.CommentSubmitted)

        assertTrue(social.sentComments.isEmpty())
    }

    @Test
    fun `a sent comment goes to the top and clears the draft`() = runTest {
        repository.details = ApiResult.Success(details())
        social.commentPages[0] = ApiResult.Success(PlaceCommentPage(items = listOf(comment("c-1"))))
        social.addCommentResult = ApiResult.Success(comment("c-2", isMine = true))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDraftChanged("Zo'r joy"))
        viewModel.onEvent(PlaceDetailsEvent.CommentSubmitted)

        assertEquals(listOf("Zo'r joy"), social.sentComments)
        assertEquals(
            listOf("c-2", "c-1"),
            (viewModel.state.value.comments as ScreenState.Content).data.map { it.id },
        )
        assertEquals("", viewModel.state.value.commentDraft)
    }

    @Test
    fun `a rejected comment keeps the text in the field`() = runTest {
        // Стереть написанное, не отправив его, — потерять работу человека.
        repository.details = ApiResult.Success(details())
        social.addCommentResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDraftChanged("Zo'r joy"))
        viewModel.onEvent(PlaceDetailsEvent.CommentSubmitted)

        assertEquals("Zo'r joy", viewModel.state.value.commentDraft)
        assertEquals(ApiError.NoConnection, viewModel.state.value.commentFailure?.error)
        assertFalse(viewModel.state.value.sendingComment)
    }

    @Test
    fun `deleting a comment asks first and then removes it`() = runTest {
        repository.details = ApiResult.Success(details())
        val mine = comment("c-1", isMine = true)
        social.commentPages[0] = ApiResult.Success(
            PlaceCommentPage(items = listOf(mine, comment("c-2"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteRequested(mine))
        assertEquals(mine, viewModel.state.value.confirmDeleteComment)
        assertTrue(social.deletedComments.isEmpty())

        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteConfirmed)

        assertEquals(listOf("c-1"), social.deletedComments)
        assertEquals(
            listOf("c-2"),
            (viewModel.state.value.comments as ScreenState.Content).data.map { it.id },
        )
    }

    @Test
    fun `a dismissed dialog deletes nothing`() = runTest {
        repository.details = ApiResult.Success(details())
        val mine = comment("c-1", isMine = true)
        social.commentPages[0] = ApiResult.Success(PlaceCommentPage(items = listOf(mine)))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteRequested(mine))
        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteDismissed)

        assertNull(viewModel.state.value.confirmDeleteComment)
        assertTrue(social.deletedComments.isEmpty())
    }

    @Test
    fun `the last deleted comment leaves the list empty, not broken`() = runTest {
        repository.details = ApiResult.Success(details())
        val mine = comment("c-1", isMine = true)
        social.commentPages[0] = ApiResult.Success(PlaceCommentPage(items = listOf(mine)))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteRequested(mine))
        viewModel.onEvent(PlaceDetailsEvent.CommentDeleteConfirmed)

        assertTrue(viewModel.state.value.comments is ScreenState.Empty)
    }

    @Test
    fun `more comments are appended without duplicates`() = runTest {
        repository.details = ApiResult.Success(details())
        social.commentPages[0] = ApiResult.Success(
            PlaceCommentPage(items = listOf(comment("c-1")), hasMore = true),
        )
        social.commentPages[1] = ApiResult.Success(
            PlaceCommentPage(items = listOf(comment("c-1"), comment("c-2"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.MoreCommentsRequested)

        assertEquals(
            listOf("c-1", "c-2"),
            (viewModel.state.value.comments as ScreenState.Content).data.map { it.id },
        )
        assertFalse(viewModel.state.value.hasMoreComments)
    }

    private fun viewModel(clock: Clock = mondayAt("12:00")) = PlaceDetailsViewModel(
        repository = repository,
        socialRepository = social,
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private fun comment(id: String, isMine: Boolean = false) = PlaceComment(
        id = id,
        authorId = if (isMine) "u-1" else "u-2",
        text = "Zo'r",
        createdAt = Instant.parse("2026-08-30T10:15:30Z"),
        isMine = isMine,
    )

    private fun details(
        isOpenNow: Boolean = true,
        hours: List<OpeningHours> = listOf(workingDay(DayOfWeek.MONDAY)),
        phone: String? = null,
        point: GeoPoint? = null,
        capabilities: PlaceCapabilities = PlaceCapabilities(),
        reviews: List<Review> = emptyList(),
    ) = PlaceDetails(
        place = place(PLACE_ID, name = "Osh markazi", isOpenNow = isOpenNow, point = point),
        description = "Eng mazali osh",
        hours = hours,
        contacts = PlaceContacts(phone = phone),
        capabilities = capabilities,
        reviews = reviews,
    )

    private fun workingDay(day: DayOfWeek) =
        OpeningHours(day, LocalTime.of(9, 0), LocalTime.of(18, 0))

    private fun review(id: String) = Review(
        id = id,
        author = "Ali",
        rating = 5,
        text = "Zo'r",
        createdAt = Instant.parse("2026-08-25T10:15:30Z"),
    )

    /**
     * Часы фиксируем в UTC и подбираем момент так, чтобы в ташкентской зоне
     * (UTC+5) это был понедельник указанного времени.
     */
    private fun mondayAt(time: String): Clock {
        val local = LocalTime.parse(time)
        val instant = Instant.parse("2026-08-24T00:00:00Z")
            .plusSeconds(local.toSecondOfDay().toLong() - TASHKENT_OFFSET_SECONDS)
        return Clock.fixed(instant, ZoneOffset.UTC)
    }

    private companion object {
        const val PLACE_ID = "p-1"
        const val TASHKENT_OFFSET_SECONDS = 5L * 60 * 60
    }
}
