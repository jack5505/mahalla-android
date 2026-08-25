package uz.mahalla.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Состояния компонентов кита, которые можно проверить без Compose: логика
 * доступности кнопки и полнота таблицы тонов.
 */
class ComponentStateTest {

    @Test
    fun `default button is clickable`() {
        assertTrue(ButtonState.Default.isClickable)
    }

    @Test
    fun `disabled button is not clickable`() {
        assertFalse(ButtonState.Disabled.isClickable)
    }

    @Test
    fun `loading button is not clickable but stays enabled`() {
        // Разница важна для TalkBack: «недоступно» и «идёт загрузка» — разные
        // сообщения, поэтому loading не сводится к enabled = false.
        assertFalse("двойная отправка формы недопустима", ButtonState.Loading.isClickable)
        assertTrue(ButtonState.Loading.enabled)
        assertTrue(ButtonState.Loading.loading)
    }

    @Test
    fun `disabled and loading is still not clickable`() {
        assertFalse(ButtonState(enabled = false, loading = true).isClickable)
    }

    @Test
    fun `every button variant is covered by the kit`() {
        assertEquals(
            listOf(
                MahallaButtonVariant.Primary,
                MahallaButtonVariant.Secondary,
                MahallaButtonVariant.Ghost,
                MahallaButtonVariant.Destructive,
            ),
            MahallaButtonVariant.entries.toList(),
        )
    }

    @Test
    fun `every tone has a place in the palette`() {
        // Тон без пары цветов уронил бы `MahallaTone.colors()` в рантайме —
        // when там исчерпывающий, и этот тест фиксирует состав enum.
        assertEquals(6, MahallaTone.entries.size)
        assertTrue(MahallaTone.entries.containsAll(listOf(MahallaTone.Success, MahallaTone.Error)))
    }
}
