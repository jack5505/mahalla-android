package uz.mahalla.feature.map.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.runtime.image.ImageProvider
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
 * знать про Hilt. Поток инициализации выбирает сам `MapKitInitializer` —
 * MapKit требует главного, и уводить его в фон нельзя.
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
    // remember + LaunchedEffect, а не produceState: тот же смысл, но со
    // ссылочным типом за `by` lint (ProduceStateDoesNotAssignValue) присваивание
    // не видит и ронял сборку. Ключи те же — смена initializer или тап
    // «Повторить» перезапускают инициализацию.
    var engineState by remember(initializer, retryKey) {
        mutableStateOf<MapEngineState?>(null)
    }
    LaunchedEffect(initializer, retryKey) {
        engineState = initializer.ensureInitialized()
    }

    Box(modifier = modifier) {
        when (engineState) {
            // null — инициализация ещё идёт. Первый раз она грузит нативную
            // библиотеку и заметна, поэтому под картой — ровная заглушка
            // цвета скелетона, а не белая дыра.
            null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalMahallaColors.current.skeleton),
            )

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
    /**
     * Ключ — подпись, а не число мест: всё, что больше сотни, рисуется как
     * «99+», и по числу кэш хранил бы попиксельно одинаковые битмапы на каждый
     * размер кучи (100, 101, 102…).
     */
    private val clusters = mutableMapOf<String, ImageProvider>()

    val marker: ImageProvider = ImageProvider.fromBitmap(
        MarkerIcons.place(markerSizePx, fillColor, strokeColor, selected = false),
    )

    val selectedMarker: ImageProvider = ImageProvider.fromBitmap(
        MarkerIcons.place(markerSizePx, fillColor, strokeColor, selected = true),
    )

    fun forMarker(selected: Boolean): ImageProvider = if (selected) selectedMarker else marker

    fun forCluster(size: Int): ImageProvider = clusters.getOrPut(MarkerIcons.clusterLabel(size)) {
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
     * Метки по id маркера — для быстрого пути [applyMarkers]: сменить иконку
     * готовой метки дешевле, чем пересобрать кластеризованную коллекцию.
     */
    private val placemarks = mutableMapOf<String, PlacemarkMapObject>()

    /**
     * Было ли положение камеры выставлено хоть раз. Нужно только затем, чтобы
     * первый кадр не анимировался: сама позиция сверяется с фактическим
     * положением карты, а не с последним запросом: иначе повторный запрос той же
     * камеры («моё местоположение» после того, как пользователь отпанорамировал
     * в сторону) не сработал бы.
     */
    private var cameraApplied = false

    /**
     * Последняя камера, пришедшая сверху. Нужна только слушателю: подтверждение
     * своего же движения наверх не отдаём.
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
        cluster.addClusterTapListener(clusterTapListenerRef)
    }

    private val cameraListener = CameraListener {
            _, position: CameraPosition, _: CameraUpdateReason, finished: Boolean ->
        if (!finished) return@CameraListener
        val moved = position.toCanvasPosition()
        // Своё же движение камеры обратно наверх не отдаём — иначе экран и
        // карта будут по кругу подтверждать друг другу одну и ту же позицию.
        val requested = requestedCamera
        if (requested == null || !MapCameraFit.isSamePosition(moved, requested)) {
            onCameraChanged(moved)
        }
    }

    /**
     * Обёртки для подписок держатся полями и переиспользуются при отписке:
     * сравнивает MapKit сам `WeakReference` или его referent — из API не
     * следует, а одна и та же ссылка верна при любом из двух вариантов.
     */
    private val tapListenerRef = WeakReference(tapListener)
    private val clusterTapListenerRef = WeakReference(clusterTapListener)
    private val clusterListenerRef = WeakReference(clusterListener)
    private val cameraListenerRef = WeakReference(cameraListener)

    private var icons: MarkerIconCache? = null
    private var collection: ClusterizedPlacemarkCollection? = null

    init {
        map.addCameraListener(cameraListenerRef)
    }

    fun applyCamera(camera: MapCameraPosition) {
        val current = map.cameraPosition.toCanvasPosition()
        requestedCamera = camera
        // Карта уже там: либо экран прислал ту же позицию, либо это отражение
        // собственного жеста пользователя, вернувшегося через onCameraChanged.
        if (cameraApplied && MapCameraFit.isSamePosition(current, camera)) return
        // Первый кадр — без анимации: полёт от нулевого меридиана к городу
        // пользователя не информация, а секунда ожидания.
        val animated = cameraApplied
        cameraApplied = true
        moveCamera(camera, animated)
    }

    fun applyMarkers(markers: List<MapMarkerUi>, iconCache: MarkerIconCache) {
        val iconsChanged = icons !== iconCache
        icons = iconCache
        val diff = diffMarkers(appliedMarkers, markers)
        if (diff.isEmpty && !iconsChanged && collection != null) return

        val target = collection ?: map.mapObjects
            .addClusterizedPlacemarkCollection(clusterListenerRef)
            .also { collection = it }

        // Быстрый путь: состав и координаты те же, поменялся только вид. Тап по
        // маркеру приходит именно так, а полная пересборка на нём заставила бы
        // моргнуть всю выдачу и пересчитать кластеры.
        if (diff.isAppearanceOnly && !iconsChanged) {
            appliedMarkers = markers
            diff.changed.forEach { marker ->
                placemarks[marker.id]?.setIcon(iconCache.forMarker(marker.selected))
            }
            return
        }

        appliedMarkers = markers
        target.clear()
        placemarks.clear()
        markers.forEach { marker ->
            // Метка настраивается внутри коллбэка: MapKit 4.x пересчитывает
            // кластеры на каждом изменении готовой метки, а так она приезжает
            // в коллекцию уже собранной.
            target.addPlacemark { placemark ->
                placemark.geometry = Point(marker.point.latitude, marker.point.longitude)
                placemark.setIcon(iconCache.forMarker(marker.selected))
                placemark.userData = marker.id
                placemark.addTapListener(tapListenerRef)
                placemarks[marker.id] = placemark
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
        map.removeCameraListener(cameraListenerRef)
        collection?.clear()
        collection = null
        placemarks.clear()
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

/** Позиция камеры MapKit в модель полотна: наклон и азимут карта не использует. */
private fun CameraPosition.toCanvasPosition() = MapCameraPosition(
    target = MapCoordinates(target.latitude, target.longitude),
    zoom = zoom,
)

private val MARKER_SIZE = 20.dp
private val CLUSTER_SIZE = 36.dp

/** Радиус кластеризации в dp — рекомендация MapKit для точек размером с маркер. */
private const val CLUSTER_RADIUS_DP = 60.0
private const val CLUSTER_MIN_ZOOM = 15
private const val CLUSTER_ZOOM_STEP = 2f
private const val CAMERA_ANIMATION_SECONDS = 0.4f
private const val NO_AZIMUTH = 0f
private const val NO_TILT = 0f
