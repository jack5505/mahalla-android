package uz.mahalla.feature.map.ui.picker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.map.canvas.MapCanvas
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.map.ui.LOCATION_PERMISSIONS
import uz.mahalla.feature.map.ui.LocationNotice
import uz.mahalla.feature.map.ui.MapBannerRow
import uz.mahalla.feature.map.ui.MapBannerSurface
import uz.mahalla.feature.map.ui.MapControls
import uz.mahalla.feature.map.ui.MapOverlayElevation
import uz.mahalla.feature.map.ui.hasLocationPermission
import uz.mahalla.feature.map.ui.locationNoticeText
import uz.mahalla.ui.theme.Spacing

/**
 * Выбор точки на карте (issue #90).
 *
 * Метка неподвижна в центре экрана, карта ездит под ней: попасть пальцем в
 * нужный дом так проще, чем тапом (под пальцем точка не видна), и не нужен
 * отдельный слушатель тапа по карте.
 *
 * Адрес по точке не подставляется: взятый в проект вариант MapKit — `lite`, в
 * нём нет геокодера, а угадывать адрес по координатам на клиенте нечем.
 * Поэтому карта уточняет координаты, а строку адреса человек пишет сам.
 */
@Composable
fun MapPickerScreen(
    onPicked: (MapPoint) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onEvent(MapPickerEvent.LocationPermissionResult(granted.values.any { it }))
    }

    // Разрешение могли выдать в онбординге (3.6) или в настройках устройства,
    // пока экран был в фоне: спрашивать его тапом второй раз незачем.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MapPickerEvent.LocationPermissionChecked(context.hasLocationPermission()))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MapPickerEffect.Picked -> onPicked(effect.point)
                MapPickerEffect.RequestLocationPermission ->
                    permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
        }
    }

    MapPickerContent(
        initializer = viewModel.mapInitializer,
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun MapPickerContent(
    initializer: MapKitInitializer,
    state: MapPickerState,
    onEvent: (MapPickerEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.map_picker_title), onBack = onBack)

        Box(modifier = Modifier.fillMaxSize()) {
            MapCanvas(
                initializer = initializer,
                // Маркеров нет: единственная точка — та, что под меткой в
                // центре, и рисовать её вторым объектом на карте значило бы
                // показать две точки там, где выбирается одна.
                markers = emptyList(),
                camera = state.camera,
                modifier = Modifier.fillMaxSize(),
                showUserLocation = state.showUserLocation,
                onCameraChanged = { camera -> onEvent(MapPickerEvent.CameraMoved(camera)) },
            )

            CenterPin(modifier = Modifier.align(Alignment.Center))

            val notice = state.locationNotice
            if (notice != null) {
                MapBannerSurface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(Spacing.gutter),
                ) {
                    MapBannerRow(
                        text = locationNoticeText(notice),
                        actionLabel = stringResource(R.string.action_close),
                        onAction = { onEvent(MapPickerEvent.NoticeDismissed) },
                    )
                }
            }

            MapControls(
                isLocating = state.isLocating,
                onZoomIn = { onEvent(MapPickerEvent.ZoomInClicked) },
                onZoomOut = { onEvent(MapPickerEvent.ZoomOutClicked) },
                onMyLocation = { onEvent(MapPickerEvent.MyLocationClicked) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(Spacing.gutter),
            )

            PickerFooter(
                state = state,
                onConfirm = { onEvent(MapPickerEvent.ConfirmClicked) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.gutter),
            )
        }
    }
}

/**
 * Метка выбора. Смещена вверх на половину своей высоты: у булавки остриё внизу
 * картинки, и без смещения человек выбирал бы точку на полсантиметра ниже той,
 * куда смотрит.
 */
@Composable
private fun CenterPin(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Place,
        contentDescription = stringResource(R.string.map_picker_pin),
        modifier = modifier
            .size(PIN_SIZE)
            .offset(y = -PIN_SIZE / 2),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Подсказка, координаты и кнопка подтверждения. Координаты показаны цифрами:
 * адреса у точки нет (в `lite`-варианте MapKit нет геокодера), и сверить выбор
 * человеку больше нечем.
 */
@Composable
private fun PickerFooter(
    state: MapPickerState,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = MapOverlayElevation,
        shadowElevation = MapOverlayElevation,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = stringResource(R.string.map_picker_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = state.point?.formatted()
                    ?: stringResource(R.string.map_picker_resolving),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MahallaButton(
                text = stringResource(R.string.map_picker_confirm),
                onClick = onConfirm,
                // Пока начальная позиция не найдена, подтверждать нечего:
                // человек согласился бы с точкой, которой не выбирал.
                state = ButtonState(enabled = state.canConfirm),
            )
        }
    }
}

private val PIN_SIZE = 40.dp

/**
 * Превью только надстройки: сам `MapCanvas` в превью не поднимается — MapKit
 * требует настоящего устройства и ключа.
 */
@ThemeLanguagePreviews
@Composable
private fun MapPickerOverlayPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            CenterPin()
            PickerFooter(
                state = MapPickerState(resolvingStart = false),
                onConfirm = {},
            )
            MapBannerSurface {
                MapBannerRow(
                    text = locationNoticeText(LocationNotice.Unavailable),
                    actionLabel = stringResource(R.string.action_close),
                    onAction = {},
                )
            }
        }
    }
}
