package uz.mahalla.feature.cinema.ui.poster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.ui.CinemaFailure
import uz.mahalla.feature.cinema.ui.MoviePoster
import uz.mahalla.feature.cinema.ui.prefersUzbekTitle
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Афиша кинотеатра (issue #106): что здесь идёт. Тап по фильму ведёт на его
 * карточку, где выбирают день и сеанс.
 */
@Composable
fun CinemaScreen(
    onOpenMovie: (movieId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CinemaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(CinemaEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CinemaEffect.OpenMovie -> onOpenMovie(effect.movieId)
            }
        }
    }

    CinemaContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun CinemaContent(
    state: CinemaState,
    onEvent: (CinemaEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.cinema_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(CinemaEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                movieItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.movieItems(
    state: CinemaState,
    onEvent: (CinemaEvent) -> Unit,
) {
    when (val movies = state.movies) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Пустая афиша — не ошибка: кинотеатр просто не завёл фильмов (или
        // завёл их под другим заведением — ручка афиши общая на платформу).
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.cinema_empty_title),
                description = stringResource(R.string.cinema_empty_description),
                icon = Icons.Outlined.Movie,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            CinemaFailure(
                failure = movies.failure,
                onRetry = { onEvent(CinemaEvent.Retry) },
            )
        }

        is ScreenState.Content -> items(movies.data, key = Movie::id) { movie ->
            MovieRow(
                movie = movie,
                onClick = { onEvent(CinemaEvent.MovieClicked(movie.id)) },
            )
        }
    }
}

@Composable
private fun MovieRow(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MoviePoster()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                Text(
                    text = movie.displayTitle(prefersUzbekTitle()).takeIf { it.isNotEmpty() }
                        ?: stringResource(R.string.cinema_movie_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                movie.genre?.let { genre ->
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgMuted,
                    )
                }
                movie.durationMinutes?.let { minutes ->
                    Text(
                        text = pluralStringResource(
                            R.plurals.cinema_movie_duration,
                            minutes,
                            minutes,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgMuted,
                    )
                }
                // Возрастное ограничение — не украшение: с ним решают, идти ли
                // с ребёнком.
                movie.ageRating?.let { rating -> MahallaBadge(text = rating) }
            }
        }
    }
}

private const val LIST_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun CinemaPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        CinemaContent(
            state = CinemaState(
                placeName = "Cinema Park",
                movies = ScreenState.Content(
                    listOf(
                        Movie(
                            id = "m-1",
                            title = "Dune",
                            titleUz = "Qum sayyorasi",
                            genre = "Fantastika",
                            durationMinutes = 155,
                            ageRating = "16+",
                        ),
                        Movie(id = "m-2", title = "Ilhom", genre = "Drama"),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
