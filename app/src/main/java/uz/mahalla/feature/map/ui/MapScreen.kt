package uz.mahalla.feature.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.feature.map.domain.MapCluster
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Карта (эпик 4.2).
 *
 * **Отрисовка карты — заглушка.** Выбор картографического SDK (Yandex MapKit
 * или Google Maps) — открытый блокер эпика: он определяет ключи, лицензию и
 * вёрстку, и подставлять один SDK «пока что» значит потом выкидывать вместе с
 * ключами. Всё, что от SDK не зависит — загрузка мест, кластеризация, выбор
 * маркера, «моё местоположение» — реализовано и покрыто тестами; на месте
 * полотна карты пока список маркеров, который SDK заменит целиком.
 */
@Composable
fun MapScreen(
    onPlaceClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MapEffect.OpenPlace -> onPlaceClick(effect.placeId)
                // Камерой и разрешением займётся слой SDK, когда он появится.
                is MapEffect.MoveCamera -> Unit
                MapEffect.RequestLocation -> Unit
            }
        }
    }

    MapContent(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

@Composable
fun MapContent(
    state: MapState,
    onEvent: (MapEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.map_title), onBack = onBack)

        ScreenStateHost(
            state = state.places,
            onRetry = { onEvent(MapEvent.Retry) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                MapCanvasPlaceholder(state = state, onEvent = onEvent)
                ClusterList(
                    clusters = state.clusters,
                    selectedClusterId = state.selectedClusterId,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Место будущего полотна карты. Показывает то, что уже посчитано (число
 * маркеров и зум) и не притворяется картой — иначе экран выглядел бы готовым.
 */
@Composable
private fun MapCanvasPlaceholder(
    state: MapState,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = stringResource(R.string.map_sdk_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            Text(
                text = stringResource(R.string.map_markers_count, state.markerCount, state.zoom),
                style = MaterialTheme.typography.labelLarge,
                color = LocalMahallaColors.current.fgMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MahallaIconButton(
                    icon = Icons.Outlined.Remove,
                    contentDescription = stringResource(R.string.map_zoom_out),
                    onClick = { onEvent(MapEvent.ZoomChanged(state.zoom - 1)) },
                )
                MahallaIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.map_zoom_in),
                    onClick = { onEvent(MapEvent.ZoomChanged(state.zoom + 1)) },
                )
                MahallaIconButton(
                    icon = Icons.Outlined.MyLocation,
                    contentDescription = stringResource(R.string.map_my_location),
                    onClick = { onEvent(MapEvent.MyLocationClicked) },
                )
            }
        }
    }
}

@Composable
private fun ClusterList(
    clusters: List<MapCluster>,
    selectedClusterId: String?,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
        contentPadding = PaddingValues(bottom = Spacing.gutter),
    ) {
        items(items = clusters, key = MapCluster::id) { cluster ->
            val single = cluster.single
            if (single != null && cluster.id == selectedClusterId) {
                // Раскрытый маркер — обычная карточка места: по тапу карточка
                // открывается целиком, как из выдачи.
                PlaceCard(
                    place = single.toCardUi(),
                    onClick = { onEvent(MapEvent.PlaceClicked(single.id)) },
                )
            } else {
                MarkerRow(cluster = cluster, onClick = { onEvent(MapEvent.ClusterClicked(cluster.id)) })
            }
        }
    }
}

@Composable
private fun MarkerRow(
    cluster: MapCluster,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = cluster.single?.name ?: stringResource(R.string.map_cluster_places, cluster.size)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MahallaComponentDefaults.mapMarkerMinSize)
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.card),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .sizeIn(
                        minWidth = MahallaComponentDefaults.cardIconSize,
                        minHeight = MahallaComponentDefaults.cardIconSize,
                    )
                    .background(
                        LocalMahallaColors.current.accentSoft,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = Spacing.item / 2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cluster.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
