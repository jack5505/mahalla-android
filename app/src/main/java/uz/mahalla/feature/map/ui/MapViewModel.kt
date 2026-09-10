package uz.mahalla.feature.map.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.GeoBounds
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.map.canvas.MapBounds
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.canvas.MapMarkerUi
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.UserLocationProvider
import javax.inject.Inject

/**
 * Карта (issue #65): маркеры, камера, выбор места, «моё местоположение».
 *
 * ViewModel не знает про Yandex MapKit: она отдаёт [MapMarkerUi] и
 * [uz.mahalla.feature.map.canvas.MapCameraPosition], а переводит их в примитивы
 * SDK полотно `MapCanvas`. Единственное исключение — [mapInitializer]: движок
 * поднимается лениво, а композиция не должна ходить в Hilt сама, поэтому ворота
 * инициализации приезжают на экран через ViewModel (так же это описано в KDoc
 * `MapCanvas`).
 *
 * **Маркеры грузятся по видимой области** (`places/map-bounds`, issue #168), а
 * не по радиусу вокруг человека: с радиусом сдвиг и зум камеры ничего не
 * догружали, и заведение на другом краю кадра просто отсутствовало на карте.
 * Радиусный `places/nearby` остаётся первым кадром — до первого ответа полотна
 * области ещё нет, а показать что-то нужно сразу; на карте без движка
 * (сборка без ключа) он остаётся единственным источником.
 *
 * Кластеризацию ViewModel не считает: её делает сам MapKit, сеточный
 * `MarkerClusterer` эпика 4 удалён вместе с подключением полотна (issue #65).
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val locationProvider: UserLocationProvider,
    /** Передаётся экрану как есть — сама ViewModel SDK не трогает. */
    val mapInitializer: MapKitInitializer,
) : MviViewModel<MapState, MapEvent, MapEffect>(MapState()) {

    /**
     * Координаты за время жизни экрана уже искали. Поле, а не часть состояния:
     * это память о сделанном, а не то, что рисуется. Нужно затем, чтобы возврат
     * на экран (`ON_RESUME` приходит на каждом) не запускал поиск заново.
     */
    private var locateRequested = false

    /**
     * Кадры карты. Поток, а не поле: пока палец ведёт карту, область меняется
     * десятки раз, а запрос должен уйти один — по тому, на чём человек
     * остановился.
     *
     * `SharedFlow`, а не `StateFlow`: тот молча съедает повторную отправку
     * той же области, и кадр, не изменившийся с прошлого раза, перестал бы
     * грузиться после «Повторить». [BufferOverflow.DROP_OLDEST] — по той же
     * причине, по которой стоит `collectLatest`: устаревший кадр никому не
     * нужен, и ждать его отправки незачем.
     */
    private val visibleBounds = MutableSharedFlow<MapBounds>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Область, которая уже загружена (с запасом [BOUNDS_MARGIN]). Не часть
     * состояния — это память о сделанном запросе, а не то, что рисуется.
     */
    private var loadedBounds: MapBounds? = null

    /**
     * Загрузка первого кадра. Держится, чтобы загрузка по области её дождалась:
     * радиусный ответ, приехавший вторым, затёр бы собой область — маркеры
     * снова оказались бы «вокруг человека», а не в кадре.
     */
    private var firstFrameJob: Job? = null

    init {
        load()
        observeVisibleBounds()
    }

    override fun onEvent(event: MapEvent) {
        when (event) {
            MapEvent.Retry -> load()

            is MapEvent.MarkerClicked -> onMarkerClicked(event.placeId)

            MapEvent.SelectionCleared -> select(null)

            is MapEvent.CameraMoved -> updateState { copy(camera = event.camera) }

            is MapEvent.VisibleBoundsChanged -> onVisibleBoundsChanged(event.bounds)

            MapEvent.ZoomInClicked -> updateState { copy(camera = MapCameraFit.zoomIn(camera)) }

            MapEvent.ZoomOutClicked -> updateState { copy(camera = MapCameraFit.zoomOut(camera)) }

            MapEvent.MyLocationClicked -> onMyLocationClicked()

            is MapEvent.LocationPermissionChecked -> onPermissionChecked(event.granted)

            is MapEvent.LocationPermissionResult -> onPermissionResult(event.granted)

            MapEvent.NoticeDismissed -> updateState { copy(locationNotice = null) }

            is MapEvent.PlaceClicked -> emitEffect(MapEffect.OpenPlace(event.placeId))
        }
    }

    /**
     * Первый кадр: радиус вокруг человека (`places/nearby`).
     *
     * Он же — ответ на «Повторить»: область к этому моменту известна, но
     * «Повторить» означает «перезагрузи экран», а не «догрузи кадр», и вернуть
     * его к тому, с чего экран начинается, честнее. Следующее движение камеры
     * всё равно уйдёт за областью — [loadedBounds] для этого и сбрасывается.
     */
    private fun load() {
        updateState {
            copy(places = ScreenState.Loading, markers = emptyList(), selectedPlaceId = null)
        }
        loadedBounds = null
        firstFrameJob?.cancel()
        firstFrameJob = viewModelScope.launch {
            when (val result = repository.places(DiscoveryFilters())) {
                is ApiResult.Failure -> updateState {
                    copy(places = ScreenState.Error(result.failure))
                }

                // Камера подгоняется под выдачу только здесь: после жеста это
                // значило бы отобрать карту, которую человек только что
                // подвинул сам.
                is ApiResult.Success -> showPlaces(result.data.items, fitCamera = true)
            }
        }
    }

    /**
     * Догрузка маркеров по кадру карты с дебаунсом [BOUNDS_DEBOUNCE_MILLIS].
     *
     * `collectLatest`, а не `debounce`: новая область отменяет не только
     * ожидание, но и уже начатый запрос по старой — ответ на кадр, из которого
     * человек уже ушёл, рисовать негде.
     */
    private fun observeVisibleBounds() {
        viewModelScope.launch {
            visibleBounds.collectLatest { bounds ->
                delay(BOUNDS_DEBOUNCE_MILLIS)
                loadVisible(bounds)
            }
        }
    }

    private fun onVisibleBoundsChanged(bounds: MapBounds) {
        // Вывернутая, вырожденная или бесконечная область — не «пустой кадр», а
        // ошибка счёта: запрос по ней вернул бы пустой список и выглядел бы как
        // «рядом ничего нет».
        if (!bounds.isValid) return
        visibleBounds.tryEmit(bounds)
    }

    private suspend fun loadVisible(bounds: MapBounds) {
        // Первый кадр доводится до конца, а не отменяется: именно его подгонка
        // камеры приводит карту в город человека, а первая область считается по
        // дефолтному центру Ташкента — отменив радиус, мы оставили бы человека
        // в Самарканде смотреть на ташкентские маркеры. Приведя камеру, `fit`
        // сообщит новый кадр, и `collectLatest` отменит этот сбор на `join`
        // ради свежей области.
        firstFrameJob?.join()
        // Проверка после дебаунса, а не до отправки: кадр, вернувшийся внутрь
        // загруженного (жест «дёрнул и вернул»), должен отменить запрос по
        // кадру, из которого человек уже ушёл. Отсеяв его раньше, мы не
        // отменили бы ничего — и на карту приехали бы маркеры чужого кадра.
        if (loadedBounds?.contains(bounds) == true) return
        val requested = bounds.expandedBy(BOUNDS_MARGIN)
        when (val result = repository.placesInBounds(requested.toGeoBounds())) {
            is ApiResult.Failure -> {
                // Маркеры на экране уже есть — снимать их из-за неудачной
                // догрузки значит наказать за жест: показанное остаётся,
                // человек видит ту же карту. Пустой экран — другое дело: там
                // кроме ошибки показать нечего.
                if (currentState.places !is ScreenState.Content) {
                    updateState {
                        copy(places = ScreenState.Error(result.failure), markers = emptyList())
                    }
                }
            }

            is ApiResult.Success -> {
                loadedBounds = requested
                showPlaces(result.data, fitCamera = false)
            }
        }
    }

    /**
     * Выдача на экран. На карту попадают только места с координатами: место без
     * точки нарисовать негде, а в счётчике маркеров оно соврало бы.
     *
     * [fitCamera] — подогнать камеру под выдачу. Пустая выдача оставляет камеру
     * там, где карта уже стоит: уносить экран в дефолтный город — потеря того,
     * что пользователь только что нашёл.
     */
    private fun showPlaces(places: List<Place>, fitCamera: Boolean) {
        val mappable = places.filter { it.point != null }
        updateState {
            // Выбранное место могло не попасть в новую выдачу: карточка о нём
            // осталась бы висеть поверх карты, на которой его маркера уже нет.
            val stillSelected = selectedPlaceId?.takeIf { id -> mappable.any { it.id == id } }
            val loaded = markersOf(mappable, stillSelected)
            copy(
                places = if (mappable.isEmpty()) ScreenState.Empty else ScreenState.Content(mappable),
                markers = loaded,
                camera = if (fitCamera) {
                    MapCameraFit.fit(points = loaded.map(MapMarkerUi::point), fallback = camera)
                } else {
                    camera
                },
                selectedPlaceId = stillSelected,
            )
        }
    }

    private fun onMarkerClicked(placeId: String) {
        // Неизвестный id — маркер из прошлой выдачи: полотно могло отдать тап,
        // пока приезжал новый список.
        if (currentState.markers.none { it.id == placeId }) return
        // Камеру при выборе не двигаем: пользователь ткнул в то, что видит, а
        // самопроизвольный полёт под пальцем читается как промах.
        select(placeId)
    }

    private fun select(placeId: String?) {
        if (currentState.selectedPlaceId == placeId) return
        updateState {
            copy(
                selectedPlaceId = placeId,
                markers = markers.map { it.copy(selected = it.id == placeId) },
            )
        }
    }

    private fun onMyLocationClicked() {
        updateState { copy(locationNotice = null) }
        if (currentState.showUserLocation) {
            locate()
        } else {
            emitEffect(MapEffect.RequestLocationPermission)
        }
    }

    /**
     * Разрешение, выданное раньше (онбординг 3.6 или настройки устройства).
     *
     * Координаты спрашиваются сразу, не дожидаясь тапа: человек, разрешивший
     * геолокацию, ждёт увидеть на карте себя, а не центр Ташкента — тем более
     * когда каталог ничего не нашёл и подгонять камеру не подо что (issue #126).
     * Молча: об этой попытке он не просил, и плашка «не удалось определить»
     * поверх карты была бы ответом на незаданный вопрос.
     */
    private fun onPermissionChecked(granted: Boolean) {
        updateState { copy(showUserLocation = granted) }
        if (granted && !locateRequested) locate(silent = true)
    }

    private fun onPermissionResult(granted: Boolean) {
        updateState {
            copy(
                showUserLocation = granted,
                locationNotice = if (granted) null else LocationNotice.PermissionDenied,
            )
        }
        if (granted) locate()
    }

    /**
     * Координаты спрашивает [UserLocationProvider]: сперва MapKit, потом
     * системный `LocationManager` (issue #126). Отсутствие координат — норма, но
     * молча оставлять карту на месте нельзя: тап без последствий выглядит как
     * поломка кнопки.
     *
     * [silent] — попытка, которую пользователь не запрашивал (разрешение уже
     * было выдано при открытии экрана): она не показывает отказ и не отбирает
     * камеру у найденных мест — маркеры человек уже видит, уходить с них он не
     * просил.
     */
    private fun locate(silent: Boolean = false) {
        if (currentState.isLocating) return
        locateRequested = true
        updateState { copy(isLocating = true) }
        viewModelScope.launch {
            val point = locationProvider.currentLocation()
            updateState {
                copy(
                    isLocating = false,
                    camera = when {
                        point == null -> camera
                        silent && markers.isNotEmpty() -> camera
                        else -> MapCameraFit.focusOn(point, camera)
                    },
                    locationNotice = when {
                        point != null -> null
                        silent -> locationNotice
                        else -> LocationNotice.Unavailable
                    },
                )
            }
        }
    }

    private fun markersOf(places: List<Place>, selectedId: String?): List<MapMarkerUi> =
        places.mapNotNull { place ->
            val point = place.point ?: return@mapNotNull null
            MapMarkerUi(
                id = place.id,
                point = MapCoordinates(point.latitude, point.longitude),
                title = place.name,
                selected = place.id == selectedId,
            )
        }

    internal companion object {
        /**
         * Сколько ждать после последнего изменения кадра. Полсекунды — на глаз
         * ещё «сразу», но панорамирование пальцем через полгорода успевает
         * закончиться, и вместо запроса на каждый кадр уходит один.
         *
         * Видна тестам: иначе они проверяли бы дебаунс по своей копии числа и
         * молча перестали бы его ловить, стоит поменять это.
         */
        const val BOUNDS_DEBOUNCE_MILLIS = 500L

        /**
         * Запас к видимой области — четверть кадра с каждой стороны. Меньше
         * смысла не имеет: маркер у самого края всё равно наполовину срезан
         * подписью, а короткий сдвиг карты должен показывать уже загруженное.
         */
        private const val BOUNDS_MARGIN = 0.25
    }
}

/** Область полотна в параметры запроса: слой `canvas` про бэкенд не знает. */
private fun MapBounds.toGeoBounds() = GeoBounds(
    minLatitude = southWest.latitude,
    minLongitude = southWest.longitude,
    maxLatitude = northEast.latitude,
    maxLongitude = northEast.longitude,
)
