package uz.mahalla.feature.discovery.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import uz.mahalla.R
import uz.mahalla.core.format.DistanceFormatter
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceSort
import uz.mahalla.ui.theme.Spacing

/**
 * Шторка фильтров (эпик 4.3): категория, расстояние, рейтинг, «открыто
 * сейчас», сортировка.
 *
 * Каждое изменение применяется сразу, без кнопки «применить»: выдача под
 * шторкой перестраивается на глазах, и отдельное подтверждение только
 * добавляет шаг.
 */
// Дефолтное значение sheetState в MahallaBottomSheet — экспериментальный API
// Material 3; opt-in нужен на стороне вызова.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersSheet(
    filters: DiscoveryFilters,
    onEvent: (SearchEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.filters_title),
    ) {
        FilterGroup(title = stringResource(R.string.filters_category)) {
            PlaceCategory.selectable.forEach { category ->
                MahallaFilterChip(
                    label = stringResource(category.labelRes),
                    selected = category in filters.categories,
                    onClick = { onEvent(SearchEvent.CategoryToggled(category)) },
                    icon = category.icon,
                )
            }
        }

        FilterGroup(title = stringResource(R.string.filters_distance)) {
            MahallaFilterChip(
                label = stringResource(R.string.filters_any),
                selected = filters.maxDistanceMeters == null,
                onClick = { onEvent(SearchEvent.DistanceSelected(null)) },
            )
            DiscoveryFilters.distancePresetsMeters.forEach { meters ->
                MahallaFilterChip(
                    label = stringResource(
                        if (DistanceFormatter.isKilometers(meters)) {
                            R.string.filters_distance_up_to_km
                        } else {
                            R.string.filters_distance_up_to_m
                        },
                        DistanceFormatter.value(meters),
                    ),
                    selected = filters.maxDistanceMeters == meters,
                    onClick = { onEvent(SearchEvent.DistanceSelected(meters)) },
                )
            }
        }

        FilterGroup(title = stringResource(R.string.filters_rating)) {
            MahallaFilterChip(
                label = stringResource(R.string.filters_any),
                selected = filters.minRating == null,
                onClick = { onEvent(SearchEvent.RatingSelected(null)) },
            )
            DiscoveryFilters.ratingPresets.forEach { rating ->
                MahallaFilterChip(
                    label = stringResource(
                        R.string.filters_rating_from,
                        RatingFormatter.format(rating).orEmpty(),
                    ),
                    selected = filters.minRating == rating,
                    onClick = { onEvent(SearchEvent.RatingSelected(rating)) },
                )
            }
        }

        MahallaSwitchRow(
            title = stringResource(R.string.filters_open_now),
            checked = filters.openNowOnly,
            onCheckedChange = { onEvent(SearchEvent.OpenNowToggled) },
        )

        Text(
            text = stringResource(R.string.filters_sort),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MahallaSegmentedControl(
            options = PlaceSort.entries.map { stringResource(it.labelRes) },
            selectedIndex = PlaceSort.entries.indexOf(filters.sort),
            onSelect = { index -> onEvent(SearchEvent.SortSelected(PlaceSort.entries[index])) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MahallaButton(
                text = stringResource(R.string.filters_reset),
                onClick = { onEvent(SearchEvent.FiltersReset) },
                modifier = Modifier.weight(1f),
                variant = MahallaButtonVariant.Ghost,
            )
            MahallaButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Группа чипов с заголовком. Ряд прокручивается вбок: при крупном шрифте
 * шесть категорий в одну строку не помещаются ни на одном экране.
 */
@Composable
private fun FilterGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item / 2),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
