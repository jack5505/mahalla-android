package uz.mahalla.feature.map.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.Cluster
import com.yandex.mapkit.map.ClusterListener
import com.yandex.mapkit.map.ClusterTapListener
import com.yandex.mapkit.map.ClusterizedPlacemarkCollection
import com.yandex.mapkit.map.MapObject
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.runtime.image.ImageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.mahalla.R
import uz.mahalla.core.ui.components.ErrorState
import uz.mahalla.feature.map.data.MapEngineState
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.lang.ref.WeakReference

/**
 * Полотно карты на Yandex MapKit (эпик 4.2).
 *
 * Composable намеренно тупой: он получает готовые [markers] и [camera] и ничего
 * не решает сам — что грузить, что выделено и куда лететь, знает `MapViewModel`.
 * Всё, что здесь есть сверх переноса состояния в SDK, — жизненный цикл
 * `MapView` и кэш иконок.
 *
 * Кластеризация — родная (`ClusterizedPlacemarkCollection`): она умеет
 * перекластеризовывать выдачу на каждом зуме, чего сеточный `MarkerClusterer`
 * домена не делает.
 */
@Composable
fun YandexMapCanvas(
    markers: List<MapMarkerUi>,
    camera: MapCameraPosition,
    modifier: Modifier = Modifier,
    showUserLocation: Boolean = false,
    onMarkerClick: (String) -> Unit = {},
    onCameraChanged: (MapCameraPosition) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = LocalMahallaColors.current
    val scheme = MaterialTheme.colorScheme

    val markerIcons = remember(density, colors.accent, scheme.surface, scheme.onSecondaryContainer) {
        MarkerIconCache(
            markerSizePx = with(density) { MARKER_SIZE.roundToPx() },
            clusterSizePx = with(density) { CLUSTER_SIZE.roundToPx() },
            fillColor = colors.accent.toArgb(),
            strokeColor = scheme.surface.toArgb(),
            textColor = scheme.onSecondaryContainer.toArgb(),
        )
    }

    // rememberUpdatedState: слушатели MapKit живут дольше рекомпозиции и иначе
    // держали бы первый пришедший коллбэк навсегда.
    val currentOnMarkerClick by rememberUpdatedState(onMarkerClick)
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)

    val mapView = remember { MapView(context) }
    val controller = remember(mapView) {
        MapCanvasController(
            mapView = mapView,
            onMarkerClick = { id -> currentOnMarkerClick(id) },
            onCameraChanged = { position -> currentOnCameraChanged(position) },
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        // MapKit тратит батарею и трафик, пока карта видима: onStop обязателен,
        // иначе свёрнутое приложение продолжает тянуть тайлы.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    MapKitFactory.getInstance().onStart()
                    mapView.onStart()
                }

                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    MapKitFactory.getInstance().onStop()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }

    LaunchedEffect(showUserLocation) { controller.setUserLocationVisible(showUserLocation) }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = {
            controller.applyCamera(camera)
            controller.applyMarkers(markers, markerIcons)
        },
    )
}

/**
 * Карта вместе с состоянием движка: пока SDK не поднят, на месте карты —
 * объяснение, а не пустой серый прямоугольник.
 *
 * [initializer] приходит из DI через ViewModel экрана: композиция не должна
 * знать про Hilt, а инициализация ходит на диск и обязана быть вне главного
 * потока.
 */
@Composable
fun MapCanvas(
    initializer: MapKitInitializer,
    markers: List<MapMarkerUi>,
    camera: MapCameraPosition,
    modifier: Modifier = Modifier,
    showUserLocation: Boolean = false,
    onMarkerClick: (String) -> Unit = {},
    onCameraChanged: (MapCameraPosition) -> Unit = {},
) {
    var retryKey by remember { mutableIntStateOf(0) }
    val engineState by produceState(initialValue = null as MapEngineState?, initializer, retryKey) {
        value = withContext(Dispatchers.IO) { initializer.ensureInitialized() }
    }

    Box(modifier = modifier) {
        when (engineState) {
            // null — инициализация ещё идёт. Скелетон здесь лишний: карта
            // поднимается за десятки миллисекунд, мигание хуже пустоты.
            null -> Unit

            MapEngineState.Ready -> YandexMapCanvas(
                markers = markers,
                camera = camera,
                modifier = Modifier.fillMaxSize(),
                showUserLocation = showUserLocation,
                onMarkerClick = onMarkerClick,
                onCameraChanged = onCameraChanged,
            )

            MapEngineState.MissingApiKey -> ErrorState(
                onRetry = { retryKey++ },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.gutter),
                title = stringResource(R.string.map_unavailable_title),
                description = stringResource(R.string.map_missing_key_description),
            )

            MapEngineState.Failed -> ErrorState(
                onRetry = { retryKey++ },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.gutter),
                title = stringResource(R.string.map_unavailable_title),
                description = stringResource(R.string.map_engine_failed_description),
            )
        }
    }
}

/** Готовые картинки маркеров: рисовать их на каждый кадр — заметный расход. */
private class MarkerIconCache(
    private val markerSizePx: Int,
    private val clusterSizePx: Int,
    private val fillColor: Int,
    private val strokeColor: Int,
    private val textColor: Int,
) {
    private val clusters = mutableMapOf<Int, ImageProvider>()

    val marker: ImageProvider = ImageProvider.fromBitmap(
        MarkerIcons.place(markerSizePx, fillColor, strokeColor, selected = false),
    )

    val selectedMarker: ImageProvider = ImageProvider.fromBitmap(
        MarkerIcons.place(markerSizePx, fillColor, strokeColor, selected = true),
    )

    fun forMarker(selected: Boolean): ImageProvider = if (selected) selectedMarker else marker

    fun forCluster(size: Int): ImageProvider = clusters.getOrPut(size) {
        ImageProvider.fromBitmap(
            MarkerIcons.cluster(clusterSizePx, fillColor, strokeColor, textColor, size),
        )
    }
}

/**
 * Состояние полотна между кадрами.
 *
 * Слушатели держатся полями: MapKit принимает их только в `WeakReference` (с
 * 4.х это прямо в сигнатуре) и сам ничего не удерживает. Локальная лямбда
 * умерла бы на первом же GC — тап по маркеру просто переставал бы работать,
 * без единой ошибки в логе.
 */
private class MapCanvasController(
    private val mapView: MapView,
    private val onMarkerClick: (String) -> Unit,
    private val onCameraChanged: (MapCameraPosition) -> Unit,
) {
    private val map get() = mapView.mapWindow.map

    private var appliedMarkers: List<MapMarkerUi> = emptyList()

    /**
     * Последняя камера, **пришедшая сверху**. Отдельно от фактического
     * положения карты: пользователь двигает карту пальцем постоянно, а
     * возвращать его на место можно только когда экран действительно попросил
     * другую позицию, — иначе любая рекомпозиция отматывала бы карту назад.
     */
    private var requestedCamera: MapCameraPosition? = null
    private var userLocationLayer: UserLocationLayer? = null

    private val tapListener = MapObjectTapListener { mapObject: MapObject, _ ->
        val id = mapObject.userData as? String
        if (id != null) onMarkerClick(id)
        id != null
    }

    private val clusterTapListener = ClusterTapListener { cluster ->
        // Тап по кластеру приближает камеру к нему, а не открывает случайное
        // место из кучи: какое из десяти пользователь имел в виду — неизвестно.
        val target = cluster.appearance.geometry
        moveCamera(
            MapCameraPosition(
                target = MapCoordinates(target.latitude, target.longitude),
                zoom = MapCameraFit.clampZoom(map.cameraPosition.zoom + CLUSTER_ZOOM_STEP),
            ),
            animated = true,
        )
        true
    }

    private val clusterListener = ClusterListener { cluster: Cluster ->
        cluster.appearance.setIcon(icons?.forCluster(cluster.size) ?: return@ClusterListener)
        cluster.addClusterTapListener(WeakReference(clusterTapListener))
    }

    private val cameraListener = CameraListener {
            _, position: CameraPosition, _: CameraUpdateReason, finished: Boolean ->
        if (!finished) return@CameraListener
        val moved = MapCameraPosition(
            target = MapCoordinates(position.target.latitude, position.target.longitude),
            zoom = position.zoom,
        )
        // Своё же движение камеры обратно наверх не отдаём — иначе экран и
        // карта будут по кругу подтверждать друг другу одну и ту же позицию.
        if (moved != requestedCamera) onCameraChanged(moved)
    }

    private var icons: MarkerIconCache? = null
    private var collection: ClusterizedPlacemarkCollection? = null

    init {
        map.addCameraListener(WeakReference(cameraListener))
    }

    fun applyCamera(camera: MapCameraPosition) {
        if (requestedCamera == camera) return
        // Первый кадр — без анимации: полёт от нулевого меридиана к городу
        // пользователя не информация, а секунда ожидания.
        val animated = requestedCamera != null
        requestedCamera = camera
        moveCamera(camera, animated)
    }

    fun applyMarkers(markers: List<MapMarkerUi>, iconCache: MarkerIconCache) {
        val iconsChanged = icons !== iconCache
        icons = iconCache
        val diff = diffMarkers(appliedMarkers, markers)
        if (diff.isEmpty && !iconsChanged && collection != null) return
        appliedMarkers = markers

        val target = collection ?: map.mapObjects
            .addClusterizedPlacemarkCollection(WeakReference(clusterListener))
            .also { collection = it }

        target.clear()
        markers.forEach { marker ->
            // Метка настраивается внутри коллбэка: MapKit 4.x пересчитывает
            // кластеры на каждом изменении готовой метки, а так она приезжает
            // в коллекцию уже собранной.
            target.addPlacemark { placemark ->
                placemark.geometry = Point(marker.point.latitude, marker.point.longitude)
                placemark.setIcon(iconCache.forMarker(marker.selected))
                placemark.userData = marker.id
                placemark.addTapListener(WeakReference(tapListener))
            }
        }
        target.clusterPlacemarks(CLUSTER_RADIUS_DP, CLUSTER_MIN_ZOOM)
    }

    fun setUserLocationVisible(visible: Boolean) {
        if (!visible) {
            userLocationLayer?.isVisible = false
            return
        }
        val layer = userLocationLayer
            ?: MapKitFactory.getInstance().createUserLocationLayer(mapView.mapWindow)
                .also { userLocationLayer = it }
        layer.isVisible = true
    }

    fun dispose() {
        map.removeCameraListener(WeakReference(cameraListener))
        collection?.clear()
        collection = null
        userLocationLayer?.isVisible = false
        userLocationLayer = null
    }

    private fun moveCamera(camera: MapCameraPosition, animated: Boolean) {
        val position = CameraPosition(
            Point(camera.target.latitude, camera.target.longitude),
            camera.zoom,
            NO_AZIMUTH,
            NO_TILT,
        )
        if (animated) {
            map.move(position, Animation(Animation.Type.SMOOTH, CAMERA_ANIMATION_SECONDS), null)
        } else {
            map.move(position)
        }
    }
}

private val MARKER_SIZE = 20.dp
private val CLUSTER_SIZE = 36.dp

/** Радиус кластеризации в dp — рекомендация MapKit для точек размером с маркер. */
private const val CLUSTER_RADIUS_DP = 60.0
private const val CLUSTER_MIN_ZOOM = 15
private const val CLUSTER_ZOOM_STEP = 2f
private const val CAMERA_ANIMATION_SECONDS = 0.4f
private const val NO_AZIMUTH = 0f
private const val NO_TILT = 0f
