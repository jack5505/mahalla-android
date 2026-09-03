package uz.mahalla.feature.place.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceCapabilities
import uz.mahalla.feature.place.domain.PlaceContacts
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import uz.mahalla.feature.place.domain.ReviewDraft
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakeUserProfileStore
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

    // Вошедший пользователь: по его id отличается свой отзыв от чужого.
    private val profileStore = FakeUserProfileStore(UserProfile(id = USER_ID))

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
            // Имя заведения уходит вместе с id: меню его из бэкенда не узнает.
            PlaceDetailsEffect.OpenVertical(PlaceAction.Queue, "p-1", "Osh markazi"),
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

    // --- Отзыв: оставить и удалить (issue #76) ---

    @Test
    fun `the form opens empty and closes without sending anything`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        assertEquals(ReviewDraft(), viewModel.state.value.reviewForm?.draft)
        assertFalse("оценки нет — отправлять нечего", viewModel.state.value.reviewForm!!.canSubmit)

        viewModel.onEvent(PlaceDetailsEvent.ReviewFormDismissed)
        assertNull(viewModel.state.value.reviewForm)
        assertTrue(repository.addedReviews.isEmpty())
    }

    @Test
    fun `the draft is sent as it was filled in`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewRatingSelected(4))
        viewModel.onEvent(PlaceDetailsEvent.ReviewTextChanged("Yaxshi"))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)

        assertEquals(
            PLACE_ID to ReviewDraft(rating = 4, text = "Yaxshi"),
            repository.addedReviews.single(),
        )
    }

    @Test
    fun `a sent review closes the form and re-asks the server for the card`() = runTest {
        // Рейтинг пересчитывает бэкенд: сложить его на клиенте значит разойтись
        // с выдачей на главной.
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()
        val requestsBefore = repository.detailsRequests

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewRatingSelected(5))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)

        assertNull(viewModel.state.value.reviewForm)
        assertEquals(requestsBefore + 1, repository.detailsRequests)
    }

    @Test
    fun `an unrated draft is not sent even if the event arrives`() = runTest {
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewTextChanged("Yaxshi"))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)

        assertTrue(repository.addedReviews.isEmpty())
        assertNotNull("форма остаётся открытой", viewModel.state.value.reviewForm)
    }

    @Test
    fun `a rejected review keeps the text and shows the answer of the server`() = runTest {
        repository.details = ApiResult.Success(details())
        repository.addReviewResult = ApiResult.Failure(ApiError.Business("REVIEW_DUPLICATE"))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewRatingSelected(5))
        viewModel.onEvent(PlaceDetailsEvent.ReviewTextChanged("Zo'r"))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)

        val form = viewModel.state.value.reviewForm!!
        assertEquals(ApiError.Business("REVIEW_DUPLICATE"), form.failure?.error)
        assertEquals("Zo'r", form.draft.text)
        assertFalse(form.submitting)
    }

    @Test
    fun `editing the form clears the previous answer of the server`() = runTest {
        repository.details = ApiResult.Success(details())
        repository.addReviewResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewRatingSelected(5))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)
        assertNotNull(viewModel.state.value.reviewForm?.failure)

        viewModel.onEvent(PlaceDetailsEvent.ReviewTextChanged("Boshqa"))

        assertNull(viewModel.state.value.reviewForm?.failure)
    }

    @Test
    fun `own review is the one whose author matches the account`() = runTest {
        repository.details = ApiResult.Success(
            details(reviews = listOf(review("r-1", authorId = "u-2"), review("r-2", authorId = USER_ID))),
        )

        val state = viewModel().state.value

        assertEquals("r-2", state.myReview?.id)
        assertTrue(state.isMine(state.data!!.reviews.last()))
        assertFalse(state.isMine(state.data!!.reviews.first()))
    }

    @Test
    fun `a review without an author is nobody's own`() = runTest {
        // Поле `userId` бэкенд может и не прислать: показать кнопку удаления
        // чужому хуже, чем не показать её владельцу.
        repository.details = ApiResult.Success(details(reviews = listOf(review("r-1"))))

        val state = viewModel().state.value

        assertNull(state.myReview)
        assertFalse(state.isMine(state.data!!.reviews.single()))
    }

    @Test
    fun `a second review is not offered while your own is on the card`() = runTest {
        repository.details = ApiResult.Success(
            details(reviews = listOf(review("r-1", authorId = USER_ID))),
        )

        assertFalse(viewModel().state.value.canAddReview)
    }

    @Test
    fun `the form is offered on an empty list of reviews`() = runTest {
        // Раньше блок отзывов исчезал целиком, и оставить первый отзыв было негде.
        repository.details = ApiResult.Success(details(reviews = emptyList()))

        assertTrue(viewModel().state.value.canAddReview)
    }

    @Test
    fun `a cached card does not offer the form`() = runTest {
        repository.details = ApiResult.Success(details().copy(fromCache = true))

        assertFalse(viewModel().state.value.canAddReview)
    }

    @Test
    fun `deletion asks for confirmation and dismissal changes nothing`() = runTest {
        val mine = review("r-1", authorId = USER_ID)
        repository.details = ApiResult.Success(details(reviews = listOf(mine)))
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteRequested(mine))
        assertEquals(mine, viewModel.state.value.reviewPendingDelete)

        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteDismissed)

        assertNull(viewModel.state.value.reviewPendingDelete)
        assertTrue(repository.deletedReviews.isEmpty())
    }

    @Test
    fun `a confirmed deletion removes the review and re-asks for the card`() = runTest {
        val mine = review("r-1", authorId = USER_ID)
        repository.details = ApiResult.Success(details(reviews = listOf(mine)))
        val viewModel = viewModel()
        val requestsBefore = repository.detailsRequests

        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteRequested(mine))
        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteConfirmed)

        assertEquals(listOf("r-1"), repository.deletedReviews)
        assertEquals(requestsBefore + 1, repository.detailsRequests)
        assertNull(viewModel.state.value.reviewPendingDelete)
        assertFalse(viewModel.state.value.deletingReview)
    }

    @Test
    fun `a failed deletion is explained by the words of the server`() = runTest {
        val mine = review("r-1", authorId = USER_ID)
        repository.details = ApiResult.Success(details(reviews = listOf(mine)))
        repository.deleteReviewResult = ApiResult.Failure(ApiError.Forbidden)
        val viewModel = viewModel()

        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteRequested(mine))
        viewModel.onEvent(PlaceDetailsEvent.ReviewDeleteConfirmed)

        assertEquals(ApiError.Forbidden, viewModel.state.value.reviewDeleteFailure?.error)
        assertNull("диалог закрыт", viewModel.state.value.reviewPendingDelete)
        assertFalse(viewModel.state.value.deletingReview)
    }

    @Test
    fun `a failed silent refresh does not erase the card`() = runTest {
        // Отзыв уже ушёл: заменить прочитанную карточку экраном ошибки значит
        // соврать, что ничего не получилось.
        repository.details = ApiResult.Success(details())
        val viewModel = viewModel()

        repository.details = ApiResult.Failure(ApiError.NoConnection)
        viewModel.onEvent(PlaceDetailsEvent.AddReviewClicked)
        viewModel.onEvent(PlaceDetailsEvent.ReviewRatingSelected(5))
        viewModel.onEvent(PlaceDetailsEvent.ReviewSubmitted)

        assertTrue(viewModel.state.value.details is ScreenState.Content)
    }

    private fun viewModel(clock: Clock = mondayAt("12:00")) = PlaceDetailsViewModel(
        repository = repository,
        profileStore = profileStore,
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
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

    private fun review(id: String, authorId: String? = null) = Review(
        id = id,
        author = "Ali",
        rating = 5,
        text = "Zo'r",
        createdAt = Instant.parse("2026-08-25T10:15:30Z"),
        authorId = authorId,
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
        const val USER_ID = "u-1"
        const val TASHKENT_OFFSET_SECONDS = 5L * 60 * 60
    }
}
