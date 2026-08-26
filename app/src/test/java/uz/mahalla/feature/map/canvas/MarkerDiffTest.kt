package uz.mahalla.feature.map.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дифф маркеров (эпик 4.2): полотно пересобирается только когда набор реально
 * изменился, иначе карта моргает на каждой рекомпозиции экрана.
 */
class MarkerDiffTest {

    private fun marker(id: String, lat: Double = 41.0, lon: Double = 69.0, selected: Boolean = false) =
        MapMarkerUi(id = id, point = MapCoordinates(lat, lon), title = id, selected = selected)

    @Test
    fun `no markers at all is an empty diff`() {
        assertTrue(diffMarkers(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `same markers give an empty diff`() {
        val markers = listOf(marker("a"), marker("b"))

        assertTrue(diffMarkers(markers, markers.toList()).isEmpty)
    }

    @Test
    fun `order does not matter`() {
        val before = listOf(marker("a"), marker("b"))
        val after = listOf(marker("b"), marker("a"))

        assertTrue(
            "перестановка выдачи не меняет карту — маркеры лежат по координатам",
            diffMarkers(before, after).isEmpty,
        )
    }

    @Test
    fun `new markers are added`() {
        val diff = diffMarkers(listOf(marker("a")), listOf(marker("a"), marker("b")))

        assertEquals(listOf("b"), diff.added.map { it.id })
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.moved.isEmpty())
        assertFalse(diff.isEmpty)
    }

    @Test
    fun `gone markers are removed`() {
        val diff = diffMarkers(listOf(marker("a"), marker("b")), listOf(marker("b")))

        assertEquals(listOf("a"), diff.removed)
        assertTrue(diff.added.isEmpty())
    }

    @Test
    fun `moved marker is not an appearance change`() {
        val diff = diffMarkers(listOf(marker("a")), listOf(marker("a", lat = 41.5)))

        assertEquals(listOf("a"), diff.moved.map { it.id })
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        // Новые координаты меняют состав кластеров — иконкой не отделаться.
        assertFalse(diff.isAppearanceOnly)
    }

    @Test
    fun `selection change touches only the appearance`() {
        val diff = diffMarkers(
            listOf(marker("a"), marker("b")),
            listOf(marker("a", selected = true), marker("b")),
        )

        assertTrue(diff.isAppearanceOnly)
        assertEquals(listOf("a"), diff.changed.map { it.id })
    }

    @Test
    fun `adding a marker is more than an appearance change`() {
        val diff = diffMarkers(listOf(marker("a")), listOf(marker("a", selected = true), marker("b")))

        assertFalse(diff.isAppearanceOnly)
    }

    @Test
    fun `title change is an appearance change`() {
        val before = listOf(marker("a"))
        val after = listOf(before.first().copy(title = "Другое имя"))

        val diff = diffMarkers(before, after)

        assertTrue(diff.isAppearanceOnly)
        assertEquals(listOf("a"), diff.changed.map { it.id })
    }

    @Test
    fun `full replacement adds and removes everything`() {
        val diff = diffMarkers(listOf(marker("a"), marker("b")), listOf(marker("c")))

        assertEquals(listOf("c"), diff.added.map { it.id })
        assertEquals(listOf("a", "b"), diff.removed)
    }
}
