package uz.mahalla.feature.cinema.ui.poster

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.Movie

/**
 * Афиша кинотеатра (issue #106): что здесь идёт.
 *
 * @param movies уже отобранные фильмы **этого** заведения
 * ([uz.mahalla.feature.cinema.domain.CinemaPoster]): ручка афиши общая на всю
 * платформу, фильтра по кинотеатру у неё нет.
 */
data class CinemaState(
    val placeName: String = "",
    val movies: ScreenState<List<Movie>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
) : UiState

sealed interface CinemaEvent : UiEvent {
    /**
     * Возврат на экран: сеансы и прокат меняет кинотеатр, и показанная час
     * назад афиша ничего не стоит.
     */
    data object ScreenResumed : CinemaEvent

    data object Refreshed : CinemaEvent
    data object Retry : CinemaEvent
    data class MovieClicked(val movieId: String) : CinemaEvent
}

sealed interface CinemaEffect : UiEffect {
    /** Карточка фильма: описание и сеансы этого кинотеатра. */
    data class OpenMovie(val movieId: String) : CinemaEffect
}
