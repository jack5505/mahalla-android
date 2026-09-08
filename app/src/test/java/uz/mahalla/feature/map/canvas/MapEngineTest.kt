package uz.mahalla.feature.map.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.map.data.MapEngineState

/**
 * Состояние движка карты, поднятое над полотном (issue #126): по нему экран
 * решает, рисовать ли управление картой. Правило простое, но ошибка в нём —
 * это кнопки масштаба и «выбрать эту точку» поверх сообщения о том, что карты
 * нет.
 */
class MapEngineTest {

    @Test
    fun `an engine that is still starting counts as usable`() {
        // Убрать управление на полсекунды и вернуть обратно хуже, чем подождать.
        assertTrue(engine(state = null).isUsable)
    }

    @Test
    fun `a running engine is usable`() {
        assertTrue(engine(MapEngineState.Ready).isUsable)
    }

    @Test
    fun `a build without the api key has no map`() {
        assertFalse(engine(MapEngineState.MissingApiKey).isUsable)
    }

    @Test
    fun `a failed engine has no map`() {
        assertFalse(engine(MapEngineState.Failed).isUsable)
    }

    private fun engine(state: MapEngineState?) = MapEngine(state = state, retry = {})
}
