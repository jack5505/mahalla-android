package uz.mahalla.feature.map.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** Подпись кластера (эпик 4.2): в кружок помещаются два знака, не больше. */
class MarkerIconsTest {

    @Test
    fun `shows the exact number while it fits`() {
        assertEquals("2", MarkerIcons.clusterLabel(2))
        assertEquals("99", MarkerIcons.clusterLabel(99))
    }

    @Test
    fun `large clusters are shortened`() {
        assertEquals("99+", MarkerIcons.clusterLabel(100))
        assertEquals("99+", MarkerIcons.clusterLabel(4_812))
    }

    @Test
    fun `nonsense count does not leak on the map`() {
        assertEquals("0", MarkerIcons.clusterLabel(-1))
    }
}
