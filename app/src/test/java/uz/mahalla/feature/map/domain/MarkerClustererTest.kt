package uz.mahalla.feature.map.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.testutil.place

/**
 * Кластеризация маркеров (эпик 4.2).
 *
 * Тесты не зависят от картографического SDK — как и сама кластеризация: SDK
 * ещё не выбран, а группировка точек от него не зависит.
 */
class MarkerClustererTest {

    @Test
    fun `places without coordinates are not shown on the map`() {
        val places = listOf(
            place("with", point = GeoPoint(41.31, 69.28)),
            place("without", point = null),
        )

        val clusters = MarkerClusterer.cluster(places, zoom = 12)

        assertEquals(1, clusters.sumOf(MapCluster::size))
        assertEquals("with", clusters.single().single?.id)
    }

    @Test
    fun `nearby places merge into one cluster`() {
        val places = listOf(
            place("a", point = GeoPoint(41.3110, 69.2790)),
            place("b", point = GeoPoint(41.3111, 69.2791)),
            place("c", point = GeoPoint(41.3112, 69.2792)),
        )

        val clusters = MarkerClusterer.cluster(places, zoom = 8)

        assertEquals(1, clusters.size)
        assertEquals(3, clusters.single().size)
        assertNull("группа — не одиночная метка", clusters.single().single)
    }

    @Test
    fun `distant places stay separate`() {
        val places = listOf(
            place("tashkent", point = GeoPoint(41.31, 69.28)),
            place("samarkand", point = GeoPoint(39.65, 66.96)),
        )

        val clusters = MarkerClusterer.cluster(places, zoom = 8)

        assertEquals(2, clusters.size)
        assertTrue(clusters.all(MapCluster::isSingle))
    }

    @Test
    fun `zooming in splits a cluster`() {
        val places = listOf(
            place("a", point = GeoPoint(41.30, 69.20)),
            place("b", point = GeoPoint(41.35, 69.30)),
        )

        assertEquals(1, MarkerClusterer.cluster(places, zoom = 3).size)
        assertEquals(2, MarkerClusterer.cluster(places, zoom = 12).size)
    }

    @Test
    fun `beyond the cluster zoom every place is its own marker`() {
        val places = listOf(
            place("a", point = GeoPoint(41.311000, 69.279000)),
            place("b", point = GeoPoint(41.311001, 69.279001)),
        )

        val clusters = MarkerClusterer.cluster(places, zoom = MarkerClusterer.MAX_CLUSTER_ZOOM)

        assertEquals(2, clusters.size)
        assertTrue(clusters.all(MapCluster::isSingle))
    }

    @Test
    fun `cluster center is the average of its points`() {
        val places = listOf(
            place("a", point = GeoPoint(41.0, 69.0)),
            place("b", point = GeoPoint(43.0, 71.0)),
        )

        val center = MarkerClusterer.center(places)

        assertEquals(42.0, center.latitude, TOLERANCE)
        assertEquals(70.0, center.longitude, TOLERANCE)
    }

    @Test
    fun `cluster order does not depend on the input order`() {
        // Иначе карта перерисовывает все метки после каждого обновления
        // выдачи, хотя точки те же.
        val a = place("a", point = GeoPoint(41.31, 69.28))
        val b = place("b", point = GeoPoint(39.65, 66.96))

        val direct = MarkerClusterer.cluster(listOf(a, b), zoom = 8).map(MapCluster::id)
        val reversed = MarkerClusterer.cluster(listOf(b, a), zoom = 8).map(MapCluster::id)

        assertEquals(direct, reversed)
    }

    @Test
    fun `cell size halves with every zoom level`() {
        assertEquals(MarkerClusterer.BASE_CELL_DEGREES, MarkerClusterer.cellSizeDegrees(0), TOLERANCE)
        assertEquals(
            MarkerClusterer.cellSizeDegrees(5) / 2,
            MarkerClusterer.cellSizeDegrees(6),
            TOLERANCE,
        )
    }

    @Test
    fun `empty input produces no clusters`() {
        assertTrue(MarkerClusterer.cluster(emptyList(), zoom = 12).isEmpty())
    }

    @Test
    fun `every cluster keeps its places for the tap handler`() {
        val places = listOf(
            place("a", point = GeoPoint(41.3110, 69.2790)),
            place("b", point = GeoPoint(41.3111, 69.2791)),
        )

        val cluster = MarkerClusterer.cluster(places, zoom = 6).single()

        assertNotNull(cluster.id)
        assertEquals(setOf("a", "b"), cluster.places.map { it.id }.toSet())
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
