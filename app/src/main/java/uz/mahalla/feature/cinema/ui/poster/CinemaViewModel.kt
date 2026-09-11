package uz.mahalla.feature.cinema.ui.poster

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.map
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.feature.cinema.domain.CinemaPoster
import uz.mahalla.navigation.CinemaRoute
import javax.inject.Inject

/**
 * Афиша кинотеатра (issue #106).
 *
 * Ручка афиши **общая на всю платформу** — `GET cinema/movies` не принимает
 * ни одного параметра, — поэтому фильмы этого заведения отбираются на клиенте
 * ([CinemaPoster.forPlace]). Отбор идёт после `toListScreenState`, а не до:
 * пустая афиша конкретного кинотеатра при непустом ответе сервера — это тоже
 * «здесь ничего не идёт», и экран говорит об этом теми же словами.
 */
@HiltViewModel
class CinemaViewModel @Inject constructor(
    private val repository: CinemaRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CinemaState, CinemaEvent, CinemaEffect>(CinemaState()) {

    private val route: CinemaRoute = savedStateHandle.toRoute()

    private var loadJob: Job? = null

    init {
        updateState { copy(placeName = route.placeName) }
        load()
    }

    override fun onEvent(event: CinemaEvent) {
        when (event) {
            // Защита от дубля (первый resume, два resume подряд) — общая, см.
            // MviViewModel.onScreenResumed (issue #145, #209).
            CinemaEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true },
                load = { load(showLoading = false) },
            )

            CinemaEvent.Refreshed -> load(showLoading = false, refreshing = true)
            CinemaEvent.Retry -> load()
            is CinemaEvent.MovieClicked -> emitEffect(CinemaEffect.OpenMovie(event.movieId))
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        updateState {
            copy(
                movies = if (showLoading) ScreenState.Loading else movies,
                isRefreshing = refreshing,
            )
        }
        loadJob = viewModelScope.launch {
            val poster = repository.movies()
                .toListScreenState()
                .map { movies -> CinemaPoster.forPlace(movies, route.placeId) }
            updateState {
                copy(
                    movies = if (poster is ScreenState.Content && poster.data.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        poster
                    },
                    isRefreshing = false,
                )
            }
        }
    }
}
