package uz.mahalla.feature.map.ui.picker

import androidx.lifecycle.SavedStateHandle
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
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCameraPosition
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.map.ui.LocationNotice
import uz.mahalla.navigation.MapPickerArgs
import uz.mahalla.testutil.FakeRequestLocationProvider
import uz.mahalla.testutil.FakeUserLocationProvider
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.fakeMapKitInitializer

/**
 * Выбор точки на карте (issue #90).
 *
 * Точка — это всегда центр камеры: метка нарисована неподвижно посередине
 * экрана. Поэтому здесь проверяется ровно то, что решает ViewModel: откуда
 * начинается карта, что уезжает в результат и что происходит, когда
 * местоположение недоступно.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapPickerViewModelTest {

    // Начальная позиция ищется без таймеров — выполняется на месте.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val locationProvider = FakeUserLocationProvider()
    private val requestLocationProvider = FakeRequestLocationProvider()

    @Test
    fun `map starts at the point picked before`() {
        val viewModel = viewModel(argument = "41.311081,69.240562")

        assertEquals(
            MapCoordinates(41.311081, 69.240562),
            viewModel.state.value.camera.target,
        )
        // Дом виден целиком: правят обычно уже выбранную точку, а не ищут
        // город заново.
        assertEquals(MapCameraFit.SINGLE_MARKER_ZOOM, viewModel.state.value.camera.zoom)
        assertFalse(viewModel.state.value.resolvingStart)
    }

    @Test
    fun `without a previous point the map starts where the person is`() {
        requestLocationProvider.location = DeviceLocation(latitude = 39.654, longitude = 66.959)

        val state = viewModel().state.value

        assertEquals(MapCoordinates(39.654, 66.959), state.camera.target)
        assertEquals(MapCameraFit.FOCUS_ZOOM, state.camera.zoom)
        assertFalse(state.resolvingStart)
    }

    @Test
    fun `garbage in the route argument is not a point`() {
        // Строку кладёт само приложение, но пережить смерть процесса и приехать
        // испорченной она может — тогда начинаем как без аргумента.
        requestLocationProvider.location = DeviceLocation(latitude = 39.654, longitude = 66.959)

        assertEquals(
            MapCoordinates(39.654, 66.959),
            viewModel(argument = "41,31;69,24").state.value.camera.target,
        )
    }

    @Test
    fun `nothing can be confirmed until the start is resolved`() {
        val viewModel = viewModel(resolveStart = false)

        assertTrue(viewModel.state.value.resolvingStart)
        assertNull(viewModel.state.value.point)
        // Иначе человек подтвердил бы центр Ташкента, которого не выбирал.
        assertFalse(viewModel.state.value.canConfirm)
    }

    @Test
    fun `the gesture of the person is older than the start search`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(resolveStart = false, startGate = gate)

        viewModel.onEvent(MapPickerEvent.CameraMoved(position(41.35, 69.30)))
        gate.complete(Unit)

        // Уводить карту из-под пальца, когда ответ приехал вторым, — промах.
        assertEquals(MapCoordinates(41.35, 69.30), viewModel.state.value.camera.target)
    }

    @Test
    fun `the confirmed point is the centre of the map`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(MapPickerEvent.CameraMoved(position(41.326543, 69.228765)))

        viewModel.onEvent(MapPickerEvent.ConfirmClicked)

        assertEquals(
            MapPickerEffect.Picked(MapPoint(41.326543, 69.228765)),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `zoom buttons stay inside the limits of the map`() {
        val viewModel = viewModel(argument = "41.311081,69.240562")

        repeat(times = 20) { viewModel.onEvent(MapPickerEvent.ZoomInClicked) }
        assertEquals(MapCameraFit.MAX_ZOOM, viewModel.state.value.camera.zoom)

        repeat(times = 40) { viewModel.onEvent(MapPickerEvent.ZoomOutClicked) }
        assertEquals(MapCameraFit.MIN_ZOOM, viewModel.state.value.camera.zoom)
    }

    @Test
    fun `my location asks for the permission when there is none`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(MapPickerEvent.MyLocationClicked)

        assertEquals(MapPickerEffect.RequestLocationPermission, viewModel.effects.first())
        assertEquals(0, locationProvider.callCount)
    }

    @Test
    fun `granted permission moves the map to the person`() {
        locationProvider.location = MapCoordinates(41.29, 69.21)
        val viewModel = viewModel()

        viewModel.onEvent(MapPickerEvent.LocationPermissionResult(granted = true))

        assertTrue(viewModel.state.value.showUserLocation)
        assertEquals(MapCoordinates(41.29, 69.21), viewModel.state.value.camera.target)
        assertNull(viewModel.state.value.locationNotice)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun `refused permission is explained, not swallowed`() {
        val viewModel = viewModel()

        viewModel.onEvent(MapPickerEvent.LocationPermissionResult(granted = false))

        // Тап без последствий читается как поломка кнопки.
        assertEquals(LocationNotice.PermissionDenied, viewModel.state.value.locationNotice)
        assertFalse(viewModel.state.value.showUserLocation)
    }

    @Test
    fun `missing coordinates are explained too`() {
        locationProvider.location = null
        val viewModel = viewModel()
        viewModel.onEvent(MapPickerEvent.LocationPermissionChecked(granted = true))

        viewModel.onEvent(MapPickerEvent.MyLocationClicked)

        assertEquals(LocationNotice.Unavailable, viewModel.state.value.locationNotice)
        assertEquals(MapCameraFit.DEFAULT.target, viewModel.state.value.camera.target)
    }

    @Test
    fun `second tap does not start a second search`() = runTest {
        locationProvider.location = MapCoordinates(41.29, 69.21)
        locationProvider.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.onEvent(MapPickerEvent.LocationPermissionChecked(granted = true))

        viewModel.onEvent(MapPickerEvent.MyLocationClicked)
        viewModel.onEvent(MapPickerEvent.MyLocationClicked)
        locationProvider.gate?.complete(Unit)

        assertEquals(1, locationProvider.callCount)
    }

    @Test
    fun `the notice can be dismissed`() {
        val viewModel = viewModel()
        viewModel.onEvent(MapPickerEvent.LocationPermissionResult(granted = false))

        viewModel.onEvent(MapPickerEvent.NoticeDismissed)

        assertNull(viewModel.state.value.locationNotice)
    }

    private fun position(latitude: Double, longitude: Double) = MapCameraPosition(
        target = MapCoordinates(latitude, longitude),
        zoom = MapCameraFit.SINGLE_MARKER_ZOOM,
    )

    /**
     * @param resolveStart `false` оставляет поиск начальной позиции незавершённым
     * — так проверяется состояние до того, как координаты нашлись.
     */
    private fun viewModel(
        argument: String? = null,
        resolveStart: Boolean = true,
        startGate: CompletableDeferred<Unit>? = null,
    ): MapPickerViewModel {
        val gate = startGate ?: CompletableDeferred<Unit>().takeIf { !resolveStart }
        return MapPickerViewModel(
            savedStateHandle = SavedStateHandle(
                if (argument == null) emptyMap() else mapOf(MapPickerArgs.POINT to argument),
            ),
            locationProvider = locationProvider,
            requestLocationProvider = GatedLocationProvider(requestLocationProvider, gate),
            mapInitializer = fakeMapKitInitializer(),
        )
    }
}

/** Начальная позиция, которую можно задержать: так проверяется состояние до её появления. */
private class GatedLocationProvider(
    private val delegate: RequestLocationProvider,
    private val gate: CompletableDeferred<Unit>?,
) : RequestLocationProvider {

    override suspend fun current(): DeviceLocation {
        gate?.await()
        return delegate.current()
    }
}
