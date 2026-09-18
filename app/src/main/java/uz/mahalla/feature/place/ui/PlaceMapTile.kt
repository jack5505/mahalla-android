package uz.mahalla.feature.place.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.ui.distanceLabel
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.canvas.MapMarkerUi
import uz.mahalla.feature.map.canvas.YandexMapCanvas
import uz.mahalla.feature.map.canvas.rememberMapEngine
import uz.mahalla.feature.map.data.MapEngineState
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Плитка карты на карточке места (макет 1b): точка-маркер и подпись
 * расстояния пешком, рядом с фото.
 *
 * Статична по одной точке — не полноценный экран карты: без своих кнопок
 * масштаба и «моего местоположения», а жесты SDK выключены
 * ([YandexMapCanvas.gesturesEnabled]), иначе тап по маленькой плитке внутри
 * прокручиваемой карточки двигал бы карту вместо списка.
 *
 * Без `MAPKIT_API_KEY` (сборка без ключа, `docs/adr/0002-yandex-mapkit.md`)
 * или при провале инициализации SDK — объяснение вместо тайлов, без кнопки
 * «Повторить»: в отличие от полноценного экрана карты (`MapUnavailable`),
 * плитка — второстепенный блок, а не тупик экрана.
 */
@Composable
fun PlaceMapTile(
    initializer: MapKitInitializer,
    point: GeoPoint,
    distanceMeters: Int,
    modifier: Modifier = Modifier,
) {
    val engine = rememberMapEngine(initializer)
    // r16 по хендоффу (1b): «плитка карты r16», как и image slot рядом.
    val shape = MaterialTheme.shapes.medium

    Column(modifier = modifier.width(TILE_WIDTH)) {
        Box(
            modifier = Modifier
                .size(width = TILE_WIDTH, height = TILE_HEIGHT)
                .clip(shape)
                .background(LocalMahallaColors.current.skeleton, shape),
        ) {
            when (engine.state) {
                MapEngineState.Ready -> YandexMapCanvas(
                    markers = listOf(
                        MapMarkerUi(
                            id = "place",
                            point = MapCoordinates(point.latitude, point.longitude),
                        ),
                    ),
                    camera = MapCameraFit.fit(
                        listOf(MapCoordinates(point.latitude, point.longitude)),
                    ),
                    modifier = Modifier.size(width = TILE_WIDTH, height = TILE_HEIGHT),
                    gesturesEnabled = false,
                )

                MapEngineState.MissingApiKey, MapEngineState.Failed -> PlaceMapTileUnavailable(
                    modifier = Modifier.size(width = TILE_WIDTH, height = TILE_HEIGHT),
                )

                // Инициализация ещё идёт — под точкой остаётся ровная заглушка
                // (фон уже нанесён), без отдельной анимации ради одной плитки.
                null -> Unit
            }
        }
        Text(
            text = stringResource(R.string.place_map_tile_caption),
            modifier = Modifier.padding(top = Spacing.item / 2),
            style = MaterialTheme.typography.labelMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = stringResource(R.string.place_map_tile_distance, distanceLabel(distanceMeters)),
            style = MaterialTheme.typography.labelLarge,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

/** Почему карты нет — коротко, без кнопки: плитка второстепенна. */
@Composable
private fun PlaceMapTileUnavailable(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(Spacing.item), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Map,
                contentDescription = null,
                modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                tint = LocalMahallaColors.current.fgMuted,
            )
            Text(
                text = stringResource(R.string.place_map_tile_unavailable),
                modifier = Modifier.padding(top = Spacing.item / 2),
                style = MaterialTheme.typography.labelSmall,
                color = LocalMahallaColors.current.fgMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val TILE_WIDTH = 150.dp
private val TILE_HEIGHT = MahallaComponentDefaults.galleryImageHeight

/**
 * Только заглушка недоступности: живой `YandexMapCanvas` в превью не
 * поднимается — MapKit требует настоящего устройства и ключа (см.
 * `MapOverlayPreview` у полноценного экрана карты).
 */
@ThemeLanguagePreviews
@Composable
private fun PlaceMapTileUnavailablePreview() {
    PreviewSurface {
        PlaceMapTileUnavailable(
            modifier = Modifier
                .size(width = TILE_WIDTH, height = TILE_HEIGHT)
                .clip(RoundedCornerShape(16.dp))
                .background(LocalMahallaColors.current.skeleton),
        )
    }
}
