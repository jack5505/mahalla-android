package uz.mahalla.feature.discovery.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaSearchField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Поиск и фильтры (эпик 4.3).
 *
 * История показывается вместо выдачи, пока запрос пустой: до первого поиска
 * показывать «ничего не найдено» не за что.
 */
@Composable
fun SearchScreen(
    onPlaceClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SearchEffect.OpenPlace -> onPlaceClick(effect.placeId)
            }
        }
    }

    SearchContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SearchContent(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.search_title), onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaSearchField(
                query = state.query,
                onQueryChange = { onEvent(SearchEvent.QueryChanged(it)) },
                onSearch = { onEvent(SearchEvent.QuerySubmitted) },
            )
            FilterBar(state = state, onEvent = onEvent)
            if (state.fromCache) {
                Text(
                    text = stringResource(R.string.state_offline_cache),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }

        if (state.showHistory) {
            HistoryList(
                history = state.history,
                onEvent = onEvent,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        } else {
            ScreenStateHost(
                state = state.results,
                onRetry = { onEvent(SearchEvent.Retry) },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
                empty = {
                    EmptyState(
                        title = stringResource(R.string.search_empty_title),
                        description = stringResource(R.string.search_empty_description),
                        actionLabel = stringResource(R.string.filters_reset),
                        onAction = { onEvent(SearchEvent.FiltersReset) },
                    )
                },
            ) { places ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                    contentPadding = PaddingValues(bottom = Spacing.gutter),
                ) {
                    items(items = places, key = { it.id }) { place ->
                        PlaceCard(
                            place = place.toCardUi(),
                            onClick = { onEvent(SearchEvent.PlaceClicked(place.id)) },
                        )
                    }
                    if (state.hasMore) {
                        item(key = "load-more") {
                            LoadMoreItem(state = state, itemCount = places.size, onEvent = onEvent)
                        }
                    }
                }
            }
        }
    }

    if (state.filtersVisible) {
        FiltersSheet(
            filters = state.filters,
            onEvent = onEvent,
            onDismiss = { onEvent(SearchEvent.FiltersClosed) },
        )
    }
}

/**
 * Хвост списка. Догрузка идёт по достижению конца — отдельная кнопка «ещё» на
 * длинной выдаче раздражает, — но после ошибки автотриггер бесполезен: список
 * не вырос, `LaunchedEffect(itemCount)` больше не сработает. Поэтому провал
 * показывает кнопку повтора, а крутилка живёт ровно столько, сколько идёт
 * запрос.
 */
@Composable
private fun LoadMoreItem(
    state: SearchState,
    itemCount: Int,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        // Одной кнопки «повторить» мало: человек должен видеть, почему хвост
        // списка не доехал (issue #34).
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(Spacing.gap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = failure.userMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = { onEvent(SearchEvent.LoadMore) },
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
            failure.server?.let { MahallaErrorDetails(server = it) }
        }
        return
    }

    LaunchedEffect(itemCount) { onEvent(SearchEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoadingMore) {
            CircularProgressIndicator(
                modifier = Modifier.size(LOADER_SIZE),
                strokeWidth = MahallaComponentDefaults.progressStrokeWidth,
            )
        } else {
            // Место под крутилку держится всегда: иначе список дёргается на
            // высоту индикатора каждый раз, когда страница догрузилась.
            Spacer(modifier = Modifier.size(LOADER_SIZE))
        }
    }
}

@Composable
private fun FilterBar(
    state: SearchState,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MahallaButton(
            text = stringResource(R.string.filters_title),
            onClick = { onEvent(SearchEvent.FiltersOpened) },
            variant = MahallaButtonVariant.Secondary,
            icon = Icons.Outlined.Tune,
            fillWidth = false,
        )
        if (state.activeFilterCount > 0) {
            MahallaBadge(
                text = state.activeFilterCount.toString(),
                tone = MahallaTone.Accent,
            )
        }
        Text(
            text = stringResource(state.filters.sort.labelRes),
            modifier = Modifier.padding(start = Spacing.item),
            style = MaterialTheme.typography.labelLarge,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

@Composable
private fun HistoryList(
    history: List<String>,
    onEvent: (SearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.gutter),
    ) {
        item(key = "history-header") {
            SectionHeader(
                title = stringResource(R.string.search_history_title),
                actionLabel = stringResource(R.string.search_history_clear),
                onAction = { onEvent(SearchEvent.HistoryCleared) },
            )
        }
        items(items = history, key = { it }) { query ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MahallaListItem(
                    title = query,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Outlined.History,
                    showChevron = false,
                    onClick = { onEvent(SearchEvent.HistoryClicked(query)) },
                )
                MahallaIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.search_history_remove, query),
                    onClick = { onEvent(SearchEvent.HistoryRemoved(query)) },
                )
            }
        }
    }
}

private val LOADER_SIZE = 24.dp
