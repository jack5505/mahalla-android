package uz.mahalla.feature.social.ui.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.ui.theme.Spacing

/**
 * «Избранное» (issue #75): места, сохранённые с их карточки.
 *
 * Список собирает репозиторий: бэкенд отдаёт только идентификаторы, карточки
 * дозапрашиваются по одной (`SocialRepository.savedPlaces`).
 */
@Composable
fun SavedPlacesScreen(
    onPlaceClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SavedPlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SavedPlacesEffect.OpenPlace -> onPlaceClick(effect.placeId)
                SavedPlacesEffect.NavigateBack -> onBack()
            }
        }
    }

    // Место могли убрать из избранного на его же карточке — список, показанный
    // до ухода, после возврата врал бы.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(SavedPlacesEvent.ScreenResumed)
    }

    SavedPlacesContent(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun SavedPlacesContent(
    state: SavedPlacesState,
    onEvent: (SavedPlacesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.saved_places_title), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(SavedPlacesEvent.Refreshed) },
        ) {
            ScreenStateHost(
                state = state.places,
                onRetry = { onEvent(SavedPlacesEvent.Retry) },
                empty = {
                    EmptyState(
                        title = stringResource(R.string.saved_places_empty_title),
                        description = stringResource(R.string.saved_places_empty_description),
                    )
                },
            ) { places ->
                SavedList(places = places, state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun SavedList(
    places: List<Place>,
    state: SavedPlacesState,
    onEvent: (SavedPlacesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.gutter),
    ) {
        items(items = places, key = { it.id }) { place ->
            PlaceCard(
                place = place.toCardUi(),
                onClick = { onEvent(SavedPlacesEvent.PlaceClicked(place.id)) },
            )
        }

        if (state.hasMore) {
            item(key = "load-more") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                    MahallaButton(
                        text = stringResource(R.string.action_show_more),
                        onClick = { onEvent(SavedPlacesEvent.LoadMore) },
                        variant = MahallaButtonVariant.Ghost,
                        state = ButtonState(loading = state.isLoadingMore),
                    )
                    // Причина отказа обязательна: кнопка «повторить» без неё —
                    // тупик (issue #34).
                    state.loadMoreFailure?.let { failure ->
                        Text(
                            text = failure.userMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun SavedPlacesPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        SavedPlacesContent(
            state = SavedPlacesState(
                places = ScreenState.Content(
                    listOf(
                        Place(
                            id = "p-1",
                            name = "Osh markazi",
                            category = PlaceCategory.Food,
                            rating = 4.7,
                            reviewCount = 128,
                            distanceMeters = 450,
                            isOpenNow = true,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
