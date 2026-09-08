package uz.mahalla.feature.cinema.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.ui.poster.CinemaEffect
import uz.mahalla.feature.cinema.ui.poster.CinemaEvent
import uz.mahalla.feature.cinema.ui.poster.CinemaViewModel
import uz.mahalla.testutil.FakeCinemaRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Афиша кинотеатра (issue #106).
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CinemaViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinemaRepository()

    /**
     * Ручка афиши общая на платформу — чужой прокат виден в её ответе, но не
     * на экране этого кинотеатра.
     */
    @Test
    fun `poster keeps only this cinema`() = runTest(mainDispatcherRule.dispatcher) {
        repository.moviesResult = ApiResult.Success(
            listOf(
                Movie(id = "mine", title = "A", placeId = PLACE),
                Movie(id = "stranger", title = "B", placeId = "other"),
            ),
        )

        val viewModel = viewModel()
        runCurrent()

        val movies = viewModel.state.value.movies as ScreenState.Content
        assertEquals(listOf("mine"), movies.data.map(Movie::id))
        assertEquals("Cinema Park", viewModel.state.value.placeName)
    }

    /**
     * Непустой ответ сервера, в котором нет ни одного фильма этого
     * кинотеатра, — это «здесь ничего не идёт», а не пустой экран без слов.
     */
    @Test
    fun `foreign only answer is an empty poster`() = runTest(mainDispatcherRule.dispatcher) {
        repository.moviesResult = ApiResult.Success(
            listOf(Movie(id = "stranger", title = "B", placeId = "other")),
        )

        val viewModel = viewModel()
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.movies)
    }

    @Test
    fun `failure keeps the server answer for the screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.moviesResult = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()

            assertEquals(
                ApiError.NoConnection,
                (viewModel.state.value.movies as ScreenState.Error).error,
            )

            viewModel.onEvent(CinemaEvent.Retry)
            runCurrent()
            assertEquals(2, repository.moviesRequests)
        }

    /** Прокат меняет кинотеатр — показанная час назад афиша ничего не стоит. */
    @Test
    fun `returning to the screen refreshes the poster`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            assertEquals(1, repository.moviesRequests)

            viewModel.onEvent(CinemaEvent.ScreenResumed)
            runCurrent()

            assertEquals(2, repository.moviesRequests)
        }

    @Test
    fun `pull to refresh does not show the skeleton`() = runTest(mainDispatcherRule.dispatcher) {
        repository.moviesResult = ApiResult.Success(
            listOf(Movie(id = "m-1", title = "A", placeId = PLACE)),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(CinemaEvent.Refreshed)

        assertTrue(viewModel.state.value.isRefreshing)
        assertTrue(viewModel.state.value.movies is ScreenState.Content)

        runCurrent()
        assertTrue(!viewModel.state.value.isRefreshing)
    }

    @Test
    fun `tap on a movie opens its card`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        val effects = mutableListOf<CinemaEffect>()
        val job = launch { effects += viewModel.effects.first() }
        viewModel.onEvent(CinemaEvent.MovieClicked("m-1"))
        runCurrent()
        job.join()

        assertEquals(CinemaEffect.OpenMovie("m-1"), effects.single())
    }

    private fun viewModel() = CinemaViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to PLACE, "placeName" to "Cinema Park"),
        ),
    )

    private companion object {
        const val PLACE = "p-1"
    }
}
