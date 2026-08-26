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
import uz.mahalla.testutil.FakeCatalogRepository
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

    private fun viewModel(clock: Clock = mondayAt("12:00")) = PlaceDetailsViewModel(
        repository = repository,
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
