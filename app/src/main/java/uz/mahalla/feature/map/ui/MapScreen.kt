package uz.mahalla.feature.map.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.feature.map.canvas.MapCanvas
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.ui.theme.Spacing

/**
 * Карта (issue #65).
 *
 * Полотно — `MapCanvas` на Yandex MapKit: маркеры, родная кластеризация, слой
 * «моё местоположение». Экран поверх карты добавляет только то, чего SDK не
 * даёт: состояние загрузки выдачи, карточку выбранного места и кнопки
 * масштаба.
 *
 * Состояния выдачи разложены руками, а не через `ScreenStateHost`: тот заменяет
 * содержимое целиком, а карта должна остаться на экране и при ошибке списка —
 * тайлы к каталогу отношения не имеют, и убирать их из-за отказа бэкенда значит
 * ломать то, что работает.
 */
@Composable
fun MapScreen(
    onPlaceClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        // Точность выбирает пользователь: слою «моё местоположение» хватит и
        // приблизительных координат, поэтому просим обе и радуемся любой.
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onEvent(MapEvent.LocationPermissionResult(granted.values.any { it }))
    }

    // Разрешение могли выдать в онбординге (3.6) или в настройках устройства,
    // пока экран был в фоне: спрашивать его тапом второй раз незачем.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MapEvent.LocationPermissionChecked(context.hasLocationPermission()))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MapEffect.OpenPlace -> onPlaceClick(effect.placeId)
                MapEffect.RequestLocationPermission ->
                    permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
        }
    }

    MapContent(
        initializer = viewModel.mapInitializer,
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun MapContent(
    initializer: MapKitInitializer,
    state: MapState,
    onEvent: (MapEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.map_title), onBack = onBack)

        Box(modifier = Modifier.fillMaxSize()) {
            MapCanvas(
                initializer = initializer,
                markers = state.markers,
                camera = state.camera,
                modifier = Modifier.fillMaxSize(),
                showUserLocation = state.showUserLocation,
                onMarkerClick = { placeId -> onEvent(MapEvent.MarkerClicked(placeId)) },
                onCameraChanged = { camera -> onEvent(MapEvent.CameraMoved(camera)) },
            )

            MapBanner(
                state = state,
                onEvent = onEvent,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(Spacing.gutter),
            )

            MapControls(
                isLocating = state.isLocating,
                onZoomIn = { onEvent(MapEvent.ZoomInClicked) },
                onZoomOut = { onEvent(MapEvent.ZoomOutClicked) },
                onMyLocation = { onEvent(MapEvent.MyLocationClicked) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(Spacing.gutter),
            )

            val selected = state.selectedPlace
            if (selected != null) {
                SelectedPlaceCard(
                    place = selected,
                    onEvent = onEvent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.gutter),
                )
            }
        }
    }
}

/**
 * Одна плашка поверх карты на всё, что нужно сказать словами: загрузка выдачи,
 * пустой ответ, ошибка бэкенда и отказ геолокации. Плашка узкая и не закрывает
 * карту — состояние списка не повод прятать тайлы.
 */
@Composable
private fun MapBanner(
    state: MapState,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = state.locationNotice
    val places = state.places
    when {
        // Отказ геолокации важнее состояния выдачи: это ответ на действие
        // пользователя, случившееся только что.
        notice != null -> MapBannerSurface(modifier = modifier) {
            MapBannerRow(
                text = locationNoticeText(notice),
                actionLabel = stringResource(R.string.action_close),
                onAction = { onEvent(MapEvent.NoticeDismissed) },
            )
        }

        places is ScreenState.Loading -> MapBannerSurface(modifier = modifier) {
            MapBannerRow(text = stringResource(R.string.state_loading))
        }

        places is ScreenState.Empty -> MapBannerSurface(modifier = modifier) {
            MapBannerRow(
                text = stringResource(R.string.map_empty_places),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onEvent(MapEvent.Retry) },
            )
        }

        places is ScreenState.Error -> MapBannerSurface(modifier = modifier) {
            MapBannerRow(
                // Текст сервера, если он его прислал (issue #34). Раскрываемых
                // подробностей здесь нет: места под них на карте попросту нет.
                text = places.failure.userMessage(),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onEvent(MapEvent.Retry) },
            )
        }
    }
}

@Composable
private fun SelectedPlaceCard(
    place: Place,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = MapOverlayElevation,
            shadowElevation = MapOverlayElevation,
        ) {
            MahallaIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = { onEvent(MapEvent.SelectionCleared) },
            )
        }
        PlaceCard(
            place = place.toCardUi(),
            onClick = { onEvent(MapEvent.PlaceClicked(place.id)) },
        )
    }
}

/**
 * Превью только надстройки над картой: сам `MapCanvas` в превью не поднимается
 * — MapKit требует настоящего устройства и ключа.
 */
@ThemeLanguagePreviews
@Composable
private fun MapOverlayPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MapBanner(state = MapState(), onEvent = {})
            MapBanner(
                state = MapState(locationNotice = LocationNotice.PermissionDenied),
                onEvent = {},
            )
            MapControls(
                isLocating = false,
                onZoomIn = {},
                onZoomOut = {},
                onMyLocation = {},
            )
            SelectedPlaceCard(place = PREVIEW_PLACE, onEvent = {})
        }
    }
}

private val PREVIEW_PLACE = Place(
    id = "preview",
    name = "Osh markazi",
    category = PlaceCategory.Food,
    rating = 4.6,
    reviewCount = 128,
    distanceMeters = 450,
    isOpenNow = true,
    point = null,
)
