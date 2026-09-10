package uz.mahalla.feature.map.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.map.canvas.MapBounds
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCameraPosition
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.canvas.MapMarkerUi
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakeUserLocationProvider
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.fakeMapKitInitializer
import uz.mahalla.testutil.place

/**
 * Карта (issue #65): маркеры, камера, выбор места и «моё местоположение».
 *
 * ViewModel говорит моделями полотна ([MapMarkerUi], [MapCameraPosition]), но
 * самого MapKit не трогает — поэтому весь экран проверяется обычным JVM-тестом,
 * без эмулятора и без ключа карты.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    // Загрузка маркеров без таймеров — выполняется на месте.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeCatalogRepository()
    private val locationProvider = FakeUserLocationProvider()

    @Test
    fun `markers are built from places with coordinates`() = runTest {
        repository.respondWith(
            listOf(
                place("a", point = GeoPoint(41.31, 69.28)),
                place("b", point = GeoPoint(39.65, 66.96)),
            ),
        )

        val state = viewModel().state.value

        assertEquals(listOf("a", "b"), state.markers.map(MapMarkerUi::id))
        assertEquals(MapCoordinates(41.31, 69.28), state.markers.first().point)
        assertEquals(2, state.markerCount)
    }

    @Test
    fun `places without coordinates never reach the map`() = runTest {
        // Место без точки нарисовать негде, а в счётчике маркеров оно соврало
        // бы про содержимое экрана.
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28)), place("b")))

        assertEquals(1, viewModel().state.value.markerCount)
    }

    @Test
    fun `an answer without any coordinates is an empty state`() = runTest {
        repository.respondWith(listOf(place("a"), place("b")))

        assertEquals(ScreenState.Empty, viewModel().state.value.places)
    }

    @Test
    fun `network error becomes an error state`() = runTest {
        repository.failWith(ApiError.NoConnection)

        assertEquals(ScreenState.Error(ApiError.NoConnection), viewModel().state.value.places)
    }

    @Test
    fun `camera is fitted to the loaded markers`() = runTest {
        val point = GeoPoint(41.31, 69.28)
        repository.respondWith(listOf(place("a", point = point)))

        val camera = viewModel().state.value.camera

        assertEquals(point.latitude, camera.target.latitude, TOLERANCE)
        assertEquals(point.longitude, camera.target.longitude, TOLERANCE)
        assertEquals(MapCameraFit.SINGLE_MARKER_ZOOM, camera.zoom, ZOOM_TOLERANCE)
    }

    @Test
    fun `an empty answer leaves the camera where the user put it`() = runTest {
        // Пустая выдача — не повод уносить экран в дефолтный город: человек
        // только что сам привёл карту в это место.
        repository.respondWith(emptyList())
        val viewModel = viewModel()
        val moved = MapCameraPosition(MapCoordinates(39.65, 66.96), zoom = 14f)

        viewModel.onEvent(MapEvent.CameraMoved(moved))
        viewModel.onEvent(MapEvent.Retry)

        assertEquals(moved, viewModel.state.value.camera)
    }

    @Test
    fun `tapping a marker selects it without moving the camera`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28)), place("b", point = GeoPoint(41.32, 69.29))))
        val viewModel = viewModel()
        val cameraBefore = viewModel.state.value.camera

        viewModel.onEvent(MapEvent.MarkerClicked("a"))

        val state = viewModel.state.value
        assertEquals("a", state.selectedPlaceId)
        assertEquals("a", state.selectedPlace?.id)
        assertTrue(state.markers.single { it.id == "a" }.selected)
        assertFalse(state.markers.single { it.id == "b" }.selected)
        assertEquals(cameraBefore, state.camera)
    }

    @Test
    fun `tapping a marker that is no longer on the map is ignored`() = runTest {
        // Тап мог приехать с полотна, пока состав маркеров менялся.
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.MarkerClicked("gone"))

        assertNull(viewModel.state.value.selectedPlaceId)
    }

    @Test
    fun `clearing the selection closes the card and unselects the marker`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.MarkerClicked("a"))

        viewModel.onEvent(MapEvent.SelectionCleared)

        assertNull(viewModel.state.value.selectedPlaceId)
        assertNull(viewModel.state.value.selectedPlace)
        assertFalse(viewModel.state.value.markers.single().selected)
    }

    @Test
    fun `reloading drops the selection`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.MarkerClicked("a"))

        viewModel.onEvent(MapEvent.Retry)

        assertNull(viewModel.state.value.selectedPlaceId)
    }

    @Test
    fun `zoom buttons move the camera and stay inside the supported range`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        val zoomBefore = viewModel.state.value.camera.zoom

        viewModel.onEvent(MapEvent.ZoomOutClicked)
        assertEquals(zoomBefore - MapCameraFit.ZOOM_STEP, viewModel.state.value.camera.zoom, ZOOM_TOLERANCE)

        repeat(times = 40) { viewModel.onEvent(MapEvent.ZoomInClicked) }
        assertEquals(MapCameraFit.MAX_ZOOM, viewModel.state.value.camera.zoom, ZOOM_TOLERANCE)

        repeat(times = 40) { viewModel.onEvent(MapEvent.ZoomOutClicked) }
        assertEquals(MapCameraFit.MIN_ZOOM, viewModel.state.value.camera.zoom, ZOOM_TOLERANCE)
    }

    @Test
    fun `a gesture on the map updates the camera in the state`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        val moved = MapCameraPosition(MapCoordinates(41.0, 69.0), zoom = 11f)

        viewModel.onEvent(MapEvent.CameraMoved(moved))

        assertEquals(moved, viewModel.state.value.camera)
    }

    @Test
    fun `my location asks the screen for a permission`() = runTest {
        // Разрешение спрашивает экран — у ViewModel нет Activity.
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.MyLocationClicked)

        assertEquals(MapEffect.RequestLocationPermission, viewModel.effects.first())
        assertEquals(0, locationProvider.callCount)
    }

    @Test
    fun `a granted permission turns the layer on and focuses the camera`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.LocationPermissionResult(granted = true))

        val state = viewModel.state.value
        assertTrue(state.showUserLocation)
        assertEquals(MapCoordinates(41.5, 69.5), state.camera.target)
        assertTrue(state.camera.zoom >= MapCameraFit.FOCUS_ZOOM)
        assertNull(state.locationNotice)
        assertFalse(state.isLocating)
    }

    @Test
    fun `a denied permission is explained instead of silence`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.LocationPermissionResult(granted = false))

        assertEquals(LocationNotice.PermissionDenied, viewModel.state.value.locationNotice)
        assertFalse(viewModel.state.value.showUserLocation)
        assertEquals(0, locationProvider.callCount)
    }

    @Test
    fun `missing coordinates are explained instead of a dead button`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        locationProvider.location = null
        val viewModel = viewModel()
        val cameraBefore = viewModel.state.value.camera

        viewModel.onEvent(MapEvent.LocationPermissionResult(granted = true))

        assertEquals(LocationNotice.Unavailable, viewModel.state.value.locationNotice)
        assertEquals(cameraBefore, viewModel.state.value.camera)
    }

    @Test
    fun `with the permission already granted my location does not ask again`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        viewModel.onEvent(MapEvent.MyLocationClicked)

        // Разрешение уже выдано: диалог не показывается, тап сразу ищет
        // координаты. Первый вызов сделала автоматическая попытка при открытии.
        assertEquals(2, locationProvider.callCount)
        assertEquals(MapCoordinates(41.5, 69.5), viewModel.state.value.camera.target)
    }

    @Test
    fun `a permission granted earlier locates the user without a tap`() = runTest {
        // Человек разрешил геолокацию в онбординге и ждёт увидеть себя на
        // карте, а каталог ничего не нашёл — подгонять камеру не подо что.
        repository.respondWith(emptyList())
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        val state = viewModel.state.value
        assertEquals(1, locationProvider.callCount)
        assertTrue(state.showUserLocation)
        assertEquals(MapCoordinates(41.5, 69.5), state.camera.target)
    }

    @Test
    fun `the automatic search does not take the camera away from found places`() = runTest {
        // Маркеры человек уже видит — уходить с них он не просил.
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()
        val cameraBefore = viewModel.state.value.camera

        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        assertEquals(cameraBefore, viewModel.state.value.camera)
    }

    @Test
    fun `the automatic search stays silent when there are no coordinates`() = runTest {
        // Об этой попытке никто не просил: плашка «не удалось определить» была
        // бы ответом на незаданный вопрос.
        repository.respondWith(emptyList())
        locationProvider.location = null
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        assertNull(viewModel.state.value.locationNotice)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun `returning to the screen does not restart the search`() = runTest {
        // ON_RESUME приходит на каждом возврате — координаты по нему ищутся раз.
        repository.respondWith(emptyList())
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))
        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        assertEquals(1, locationProvider.callCount)
    }

    @Test
    fun `a second tap does not start a second location request`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val gate = CompletableDeferred<Unit>()
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()
        // Автоматическая попытка при открытии успевает целиком: гейт ставится
        // после неё, иначе тест проверял бы не тап.
        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))
        locationProvider.gate = gate

        viewModel.onEvent(MapEvent.MyLocationClicked)
        assertTrue(viewModel.state.value.isLocating)
        viewModel.onEvent(MapEvent.MyLocationClicked)

        gate.complete(Unit)
        assertEquals(2, locationProvider.callCount)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun `the notice is dismissed by the user`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.LocationPermissionResult(granted = false))

        viewModel.onEvent(MapEvent.NoticeDismissed)

        assertNull(viewModel.state.value.locationNotice)
    }

    @Test
    fun `place tap opens the card`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.PlaceClicked("a"))

        assertEquals(MapEffect.OpenPlace("a"), viewModel.effects.first())
    }

    // --- Маркеры по видимой области (issue #168) ---

    @Test
    fun `markers are loaded for the visible area of the map`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Радиус вокруг человека — только первый кадр: заведение на другом
            // краю кадра в круг не попадает, и раньше его на карте не было.
            repository.respondWith(listOf(place("near", point = GeoPoint(41.31, 69.28))))
            repository.boundsResult = ApiResult.Success(
                listOf(place("far", point = GeoPoint(41.38, 69.35))),
            )
            val viewModel = viewModel()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            assertEquals(listOf("far"), viewModel.state.value.markers.map(MapMarkerUi::id))
            val requested = repository.requestedBounds.single()
            // Область запрашивается с запасом: короткий сдвиг карты должен
            // показывать уже загруженное, а не ходить в сеть заново.
            assertTrue(requested.minLatitude < FRAME.southWest.latitude)
            assertTrue(requested.minLongitude < FRAME.southWest.longitude)
            assertTrue(requested.maxLatitude > FRAME.northEast.latitude)
            assertTrue(requested.maxLongitude > FRAME.northEast.longitude)
        }

    @Test
    fun `panning the map sends one request instead of one per frame`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(emptyList())
            val viewModel = viewModel()

            // Палец ведёт карту: кадр меняется десятки раз.
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(frame(41.4, 69.4)))
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(frame(41.5, 69.5)))
            advanceTimeBy(MapViewModel.BOUNDS_DEBOUNCE_MILLIS - 1)
            assertTrue(repository.requestedBounds.isEmpty())

            advanceUntilIdle()

            // Запрос один — по тому кадру, на котором человек остановился.
            val requested = repository.requestedBounds.single()
            assertTrue(requested.minLatitude < 41.5)
            assertTrue(requested.maxLatitude > 41.5)
        }

    @Test
    fun `a frame inside the loaded area is not requested again`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(emptyList())
            val viewModel = viewModel()
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            // Приближение: кадр стал меньше загруженного — нового не покажет.
            viewModel.onEvent(
                MapEvent.VisibleBoundsChanged(
                    MapBounds(MapCoordinates(41.31, 69.26), MapCoordinates(41.33, 69.28)),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, repository.requestedBounds.size)
        }

    @Test
    fun `a frame reaching outside the loaded area is requested`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(emptyList())
            val viewModel = viewModel()
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(frame(41.6, 69.6)))
            advanceUntilIdle()

            assertEquals(2, repository.requestedBounds.size)
        }

    @Test
    fun `an impossible area is never requested`() = runTest(mainDispatcherRule.dispatcher) {
        // Вывернутый прямоугольник — ошибка счёта, а не пустой кадр: сервер
        // ответил бы пустым списком, и это выглядело бы как «рядом ничего нет».
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(
            MapEvent.VisibleBoundsChanged(
                MapBounds(MapCoordinates(41.40, 69.30), MapCoordinates(41.30, 69.20)),
            ),
        )
        advanceUntilIdle()

        assertTrue(repository.requestedBounds.isEmpty())
        assertEquals(listOf("a"), viewModel.state.value.markers.map(MapMarkerUi::id))
    }

    @Test
    fun `loading by area leaves the camera where the user put it`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
            repository.boundsResult = ApiResult.Success(
                listOf(place("b", point = GeoPoint(41.80, 69.90))),
            )
            val viewModel = viewModel()
            val moved = MapCameraPosition(MapCoordinates(41.32, 69.27), zoom = 13f)
            viewModel.onEvent(MapEvent.CameraMoved(moved))

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            // Подогнать камеру под догрузку значило бы отобрать карту, которую
            // человек только что подвинул сам.
            assertEquals(moved, viewModel.state.value.camera)
        }

    @Test
    fun `a failed area load keeps the markers that are already on screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
            repository.boundsResult = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            // Снять показанное из-за неудачной догрузки значит наказать за жест.
            val state = viewModel.state.value
            assertEquals(listOf("a"), state.markers.map(MapMarkerUi::id))
            assertTrue(state.places is ScreenState.Content)
        }

    @Test
    fun `a failed area load on an empty screen is an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Показать кроме ошибки нечего: радиусная выдача тоже не доехала.
            repository.failWith(ApiError.NoConnection)
            repository.boundsResult = ApiResult.Failure(ApiError.Timeout)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            assertEquals(ApiError.Timeout, (viewModel.state.value.places as ScreenState.Error).failure.error)
            assertTrue(viewModel.state.value.markers.isEmpty())
        }

    @Test
    fun `the first frame answer does not overwrite the area`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Радиусный ответ, приехав вторым, вернул бы маркеры «вокруг
            // человека» на карту, которую человек уже отвёл в сторону.
            val slowNearby = CompletableDeferred<Unit>()
            repository.pagesGate = slowNearby
            repository.respondWith(listOf(place("near", point = GeoPoint(41.31, 69.28))))
            repository.boundsResult = ApiResult.Success(
                listOf(place("far", point = GeoPoint(41.38, 69.35))),
            )
            val viewModel = viewModel()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()
            slowNearby.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("far"), viewModel.state.value.markers.map(MapMarkerUi::id))
        }

    @Test
    fun `the area waits for the first frame instead of cancelling it`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Первый кадр — единственное, что приводит камеру в город
            // человека: отменив его, мы оставили бы жителя Самарканда смотреть
            // на маркеры вокруг дефолтного центра Ташкента.
            val slowNearby = CompletableDeferred<Unit>()
            repository.pagesGate = slowNearby
            repository.respondWith(listOf(place("near", point = GeoPoint(39.65, 66.96))))
            val viewModel = viewModel()

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()
            // Область ждёт: пока радиус не ответил, запроса по ней нет.
            assertTrue(repository.requestedBounds.isEmpty())

            slowNearby.complete(Unit)
            advanceUntilIdle()

            // Камера доехала до города человека, и только потом ушёл запрос
            // по области.
            assertEquals(39.65, viewModel.state.value.camera.target.latitude, TOLERANCE)
            assertEquals(1, repository.requestedBounds.size)
        }

    @Test
    fun `a frame the user returned from does not bring foreign markers`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Жест «дёрнул и вернул» быстрее дебаунса: ответ по кадру, из
            // которого человек уже ушёл, лёг бы на карту чужими маркерами — тот
            // же симптом, из-за которого открыт issue #168.
            repository.respondWith(emptyList())
            repository.boundsResult = ApiResult.Success(
                listOf(place("wide", point = GeoPoint(41.32, 69.27))),
            )
            val viewModel = viewModel()
            // Загружена широкая область.
            val wide = MapBounds(MapCoordinates(41.20, 69.10), MapCoordinates(41.44, 69.44))
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(wide))
            advanceUntilIdle()
            repository.boundsResult = ApiResult.Success(
                listOf(place("outside", point = GeoPoint(42.50, 70.50))),
            )

            // Ушёл далеко за загруженное — и, не дождавшись дебаунса, вернулся
            // внутрь.
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(frame(42.5, 70.5)))
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            assertEquals(1, repository.requestedBounds.size)
            assertEquals(listOf("wide"), viewModel.state.value.markers.map(MapMarkerUi::id))
        }

    @Test
    fun `a new frame cancels the request that is already in flight`() =
        runTest(mainDispatcherRule.dispatcher) {
            // То, ради чего стоит collectLatest, а не debounce: ответ по кадру,
            // из которого человек ушёл, рисовать негде.
            repository.respondWith(emptyList())
            val stuck = CompletableDeferred<Unit>()
            repository.boundsGate = stuck
            repository.boundsResult = ApiResult.Success(
                listOf(place("stale", point = GeoPoint(41.32, 69.27))),
            )
            val viewModel = viewModel()
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()
            assertEquals(1, repository.requestedBounds.size)

            // Кадр сменился, пока первый запрос висит.
            repository.boundsGate = null
            repository.boundsResult = ApiResult.Success(
                listOf(place("fresh", point = GeoPoint(41.60, 69.60))),
            )
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(frame(41.6, 69.6)))
            advanceUntilIdle()
            // Зависший запрос отвечает уже никому.
            stuck.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("fresh"), viewModel.state.value.markers.map(MapMarkerUi::id))
        }

    @Test
    fun `a frame shrunk to a point is never requested`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Так выглядит visibleRegion до того, как окно карты промерено.
            repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
            val viewModel = viewModel()

            viewModel.onEvent(
                MapEvent.VisibleBoundsChanged(
                    MapBounds(MapCoordinates(0.0, 0.0), MapCoordinates(0.0, 0.0)),
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.requestedBounds.isEmpty())
            assertEquals(listOf("a"), viewModel.state.value.markers.map(MapMarkerUi::id))
        }

    @Test
    fun `a place that left the frame does not keep its card open`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
            repository.boundsResult = ApiResult.Success(
                listOf(place("b", point = GeoPoint(41.38, 69.35))),
            )
            val viewModel = viewModel()
            viewModel.onEvent(MapEvent.MarkerClicked("a"))

            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            // Карточка места, маркера которого на карте больше нет, висела бы
            // поверх пустоты.
            assertNull(viewModel.state.value.selectedPlaceId)
            assertNull(viewModel.state.value.selectedPlace)
        }

    @Test
    fun `retry reloads the screen and lets the next frame load by area again`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
            val viewModel = viewModel()
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            viewModel.onEvent(MapEvent.Retry)
            advanceUntilIdle()
            // Тот же кадр после «Повторить» грузится заново: загруженной
            // области больше нет, экран начат с нуля.
            viewModel.onEvent(MapEvent.VisibleBoundsChanged(FRAME))
            advanceUntilIdle()

            assertEquals(2, repository.requestedBounds.size)
        }

    @Test
    fun `map starts at the default camera until places arrive`() {
        assertEquals(MapCameraFit.DEFAULT, MapState().camera)
        assertTrue(MapState().markers.isEmpty())
    }

    private fun viewModel() = MapViewModel(
        repository = repository,
        locationProvider = locationProvider,
        mapInitializer = fakeMapKitInitializer(),
    )

    private companion object {
        const val TOLERANCE = 1e-6
        const val ZOOM_TOLERANCE = 1e-3f

        /** Кадр карты: то, что полотно сообщает после каждого движения. */
        val FRAME = MapBounds(
            southWest = MapCoordinates(41.30, 69.25),
            northEast = MapCoordinates(41.34, 69.29),
        )

        /** Кадр вокруг точки — размером примерно с [FRAME]. */
        fun frame(latitude: Double, longitude: Double) = MapBounds(
            southWest = MapCoordinates(latitude - 0.02, longitude - 0.02),
            northEast = MapCoordinates(latitude + 0.02, longitude + 0.02),
        )
    }
}
