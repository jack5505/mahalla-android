package uz.mahalla.feature.map.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
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

        assertEquals(1, locationProvider.callCount)
        assertEquals(MapCoordinates(41.5, 69.5), viewModel.state.value.camera.target)
    }

    @Test
    fun `a second tap does not start a second location request`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val gate = CompletableDeferred<Unit>()
        locationProvider.gate = gate
        locationProvider.location = MapCoordinates(41.5, 69.5)
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.LocationPermissionChecked(granted = true))

        viewModel.onEvent(MapEvent.MyLocationClicked)
        assertTrue(viewModel.state.value.isLocating)
        viewModel.onEvent(MapEvent.MyLocationClicked)

        gate.complete(Unit)
        assertEquals(1, locationProvider.callCount)
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
    }
}
