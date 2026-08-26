package uz.mahalla.feature.map.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.map.domain.MapCluster
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place

/**
 * Карта (эпик 4.2): маркеры, кластеризация и выбор.
 *
 * SDK карты ещё не выбран — тесты проверяют ровно ту часть, которая от него
 * не зависит и переживёт любое из двух решений.
 */
class MapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCatalogRepository()

    @Test
    fun `markers are built from places with coordinates`() = runTest {
        repository.respondWith(
            listOf(
                place("a", point = GeoPoint(41.31, 69.28)),
                place("b", point = GeoPoint(39.65, 66.96)),
            ),
        )

        val state = viewModel().state.value

        assertEquals(2, state.clusters.size)
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
    fun `zooming in rebuilds the clusters`() = runTest {
        repository.respondWith(
            listOf(
                place("a", point = GeoPoint(41.30, 69.20)),
                place("b", point = GeoPoint(41.35, 69.30)),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.ZoomChanged(3))
        assertEquals(1, viewModel.state.value.clusters.size)

        viewModel.onEvent(MapEvent.ZoomChanged(14))
        assertEquals(2, viewModel.state.value.clusters.size)
    }

    @Test
    fun `zoom is clamped to the supported range`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.ZoomChanged(-5))
        assertEquals(MapState.MIN_ZOOM, viewModel.state.value.zoom)

        viewModel.onEvent(MapEvent.ZoomChanged(99))
        assertEquals(MapState.MAX_ZOOM, viewModel.state.value.zoom)
    }

    @Test
    fun `tapping a single marker selects it and moves the camera`() = runTest {
        val point = GeoPoint(41.31, 69.28)
        repository.respondWith(listOf(place("a", point = point)))
        val viewModel = viewModel()
        val cluster = viewModel.state.value.clusters.single()

        viewModel.onEvent(MapEvent.ClusterClicked(cluster.id))

        assertEquals(cluster.id, viewModel.state.value.selectedClusterId)
        assertEquals(MapEffect.MoveCamera(point, viewModel.state.value.zoom), viewModel.effects.first())
    }

    @Test
    fun `tapping a group zooms in instead of opening a random place`() = runTest {
        repository.respondWith(
            listOf(
                place("a", point = GeoPoint(41.3110, 69.2790)),
                place("b", point = GeoPoint(41.3160, 69.2860)),
            ),
        )
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.ZoomChanged(6))
        val group = viewModel.state.value.clusters.single { it.size > 1 }
        val zoomBefore = viewModel.state.value.zoom

        viewModel.onEvent(MapEvent.ClusterClicked(group.id))

        assertTrue(viewModel.state.value.zoom > zoomBefore)
        // Выделение не ставим: после пересборки этого id может уже не быть.
        assertNull(viewModel.state.value.selectedClusterId)
    }

    @Test
    fun `selection is dropped when the clusters are rebuilt`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.ClusterClicked(viewModel.state.value.clusters.single().id))
        assertNotNull(viewModel.state.value.selectedClusterId)

        viewModel.onEvent(MapEvent.ZoomChanged(viewModel.state.value.zoom + 3))

        assertNull(viewModel.state.value.selectedClusterId)
    }

    @Test
    fun `clearing the selection closes the card`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        viewModel.onEvent(MapEvent.ClusterClicked(viewModel.state.value.clusters.single().id))

        viewModel.onEvent(MapEvent.SelectionCleared)

        assertNull(viewModel.state.value.selectedClusterId)
    }

    @Test
    fun `tapping an unknown cluster is ignored`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.ClusterClicked("c:does:not:exist"))

        assertNull(viewModel.state.value.selectedClusterId)
    }

    @Test
    fun `my location asks the screen for a permission`() = runTest {
        // Разрешение спрашивает экран — у ViewModel нет Activity.
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.MyLocationClicked)

        assertEquals(MapEffect.RequestLocation, viewModel.effects.first())
    }

    @Test
    fun `place tap opens the card`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()

        viewModel.onEvent(MapEvent.PlaceClicked("a"))

        assertEquals(MapEffect.OpenPlace("a"), viewModel.effects.first())
    }

    @Test
    fun `selected cluster is resolved from the state`() = runTest {
        repository.respondWith(listOf(place("a", point = GeoPoint(41.31, 69.28))))
        val viewModel = viewModel()
        val cluster: MapCluster = viewModel.state.value.clusters.single()

        viewModel.onEvent(MapEvent.ClusterClicked(cluster.id))

        assertEquals("a", viewModel.state.value.selectedCluster?.single?.id)
    }

    @Test
    fun `map starts at the city center until geolocation arrives`() {
        assertEquals(MapState.TASHKENT_CENTER, MapState().camera)
        assertEquals(MapState.DEFAULT_ZOOM, MapState().zoom)
    }

    private fun viewModel() = MapViewModel(repository)
}
