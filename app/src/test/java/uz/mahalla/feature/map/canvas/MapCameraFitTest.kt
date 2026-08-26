package uz.mahalla.feature.map.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Камера карты (эпик 4.2): куда смотреть и с каким зумом. */
class MapCameraFitTest {

    private val tashkent = MapCoordinates(41.311081, 69.240562)
    private val samarkand = MapCoordinates(39.627012, 66.974973)
    private val nukus = MapCoordinates(42.460131, 59.617130)

    @Test
    fun `empty result keeps the fallback camera`() {
        val fallback = MapCameraPosition(samarkand, 14f)

        assertEquals(fallback, MapCameraFit.fit(emptyList(), fallback))
    }

    @Test
    fun `default fallback is Tashkent`() {
        val position = MapCameraFit.fit(emptyList())

        assertEquals(MapCameraFit.DEFAULT_TARGET, position.target)
        assertEquals(MapCameraFit.DEFAULT_ZOOM, position.zoom, 0f)
    }

    @Test
    fun `single marker is centered on itself`() {
        val position = MapCameraFit.fit(listOf(tashkent))

        assertEquals(tashkent, position.target)
        assertEquals(MapCameraFit.SINGLE_MARKER_ZOOM, position.zoom, 0f)
    }

    @Test
    fun `several markers are centered between them`() {
        val position = MapCameraFit.fit(listOf(tashkent, samarkand))

        assertEquals((41.311081 + 39.627012) / 2, position.target.latitude, 1e-9)
        assertEquals((69.240562 + 66.974973) / 2, position.target.longitude, 1e-9)
    }

    @Test
    fun `wider spread means smaller zoom`() {
        val neighbours = MapCameraFit.fit(
            listOf(tashkent, tashkent.copy(longitude = tashkent.longitude + 0.01)),
        )
        val acrossCountry = MapCameraFit.fit(listOf(tashkent, nukus))

        assertTrue(
            "соседние улицы должны быть ближе, чем Ташкент и Нукус",
            neighbours.zoom > acrossCountry.zoom,
        )
    }

    @Test
    fun `duplicate points do not blow the zoom up`() {
        val position = MapCameraFit.fit(listOf(tashkent, tashkent, tashkent))

        // Разброс нулевой: формула дала бы бесконечность, поэтому — как для
        // одного маркера.
        assertEquals(MapCameraFit.SINGLE_MARKER_ZOOM, position.zoom, 0f)
        assertEquals(tashkent, position.target)
    }

    @Test
    fun `zoom stays inside the sdk range`() {
        val wholeWorld = MapCameraFit.fit(
            listOf(MapCoordinates(-80.0, -179.0), MapCoordinates(80.0, 179.0)),
        )

        assertEquals(MapCameraFit.MIN_ZOOM, wholeWorld.zoom, 0f)
        assertEquals(MapCameraFit.MAX_ZOOM, MapCameraFit.clampZoom(50f), 0f)
        assertEquals(MapCameraFit.MIN_ZOOM, MapCameraFit.clampZoom(-1f), 0f)
    }

    @Test
    fun `zoom buttons step and clamp`() {
        val start = MapCameraPosition(tashkent, 12f)

        assertEquals(13f, MapCameraFit.zoomIn(start).zoom, 0f)
        assertEquals(11f, MapCameraFit.zoomOut(start).zoom, 0f)
        assertEquals(
            MapCameraFit.MAX_ZOOM,
            MapCameraFit.zoomIn(start.copy(zoom = MapCameraFit.MAX_ZOOM)).zoom,
            0f,
        )
        assertEquals(
            MapCameraFit.MIN_ZOOM,
            MapCameraFit.zoomOut(start.copy(zoom = MapCameraFit.MIN_ZOOM)).zoom,
            0f,
        )
        assertEquals(tashkent, MapCameraFit.zoomIn(start).target)
    }

    @Test
    fun `my location keeps the current zoom when it is already close`() {
        val zoomedIn = MapCameraPosition(samarkand, 18f)

        val position = MapCameraFit.focusOn(tashkent, zoomedIn)

        assertEquals(tashkent, position.target)
        assertEquals(18f, position.zoom, 0f)
    }

    /**
     * По этому сравнению полотно решает, двигать карту или нет: строгое
     * равенство отматывало бы её назад после каждого жеста пользователя (SDK
     * возвращает позицию, отличающуюся в последних знаках), а повторный запрос
     * той же камеры («моё местоположение» после панорамирования) — наоборот,
     * обязан сработать.
     */
    @Test
    fun `same position tolerates sdk rounding but sees a real move`() {
        val requested = MapCameraPosition(tashkent, 14f)
        val echo = MapCameraPosition(
            MapCoordinates(tashkent.latitude + 1e-8, tashkent.longitude - 1e-8),
            14.001f,
        )
        val panned = MapCameraPosition(samarkand, 14f)
        val zoomed = MapCameraPosition(tashkent, 15f)

        assertTrue(MapCameraFit.isSamePosition(requested, echo))
        assertFalse(MapCameraFit.isSamePosition(requested, panned))
        assertFalse(MapCameraFit.isSamePosition(requested, zoomed))
    }

    @Test
    fun `my location approaches from a far camera`() {
        val faraway = MapCameraPosition(samarkand, 5f)

        val position = MapCameraFit.focusOn(tashkent, faraway)

        assertTrue("камера должна приблизиться к пользователю", position.zoom > 5f)
        assertEquals(tashkent, position.target)
    }
}
