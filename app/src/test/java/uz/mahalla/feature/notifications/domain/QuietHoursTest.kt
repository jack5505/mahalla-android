package uz.mahalla.feature.notifications.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Тихие часы (эпик 11).
 *
 * Проверяется в первую очередь интервал через полночь — ради него настройка и
 * существует («с 22:00 до 08:00»), и именно на нём ошибается наивное
 * `from <= t && t < to`: оно молчало бы весь день и звучало всю ночь.
 */
class QuietHoursTest {

    @Test
    fun `disabled quiet hours never silence`() {
        val quiet = QuietHours(enabled = false, fromMinuteOfDay = 0, toMinuteOfDay = 24 * 60)
        assertFalse(quiet.isQuiet(LocalTime.of(3, 0)))
    }

    @Test
    fun `interval inside a day silences only inside it`() {
        val quiet = QuietHours(enabled = true, fromMinuteOfDay = 13 * 60, toMinuteOfDay = 15 * 60)

        assertTrue(quiet.isQuiet(LocalTime.of(13, 0)))
        assertTrue(quiet.isQuiet(LocalTime.of(14, 30)))
        // Верхняя граница исключена: «до 15:00» значит, что в 15:00 уже звучит.
        assertFalse(quiet.isQuiet(LocalTime.of(15, 0)))
        assertFalse(quiet.isQuiet(LocalTime.of(12, 59)))
        assertFalse(quiet.isQuiet(LocalTime.of(3, 0)))
    }

    @Test
    fun `interval across midnight silences both sides of it`() {
        val quiet = QuietHours(enabled = true)

        assertEquals(22 * 60, quiet.from)
        assertEquals(8 * 60, quiet.to)
        assertTrue(quiet.isQuiet(LocalTime.of(22, 0)))
        assertTrue(quiet.isQuiet(LocalTime.of(23, 59)))
        assertTrue(quiet.isQuiet(LocalTime.of(0, 0)))
        assertTrue(quiet.isQuiet(LocalTime.of(7, 59)))
        assertFalse(quiet.isQuiet(LocalTime.of(8, 0)))
        assertFalse(quiet.isQuiet(LocalTime.of(12, 0)))
        assertFalse(quiet.isQuiet(LocalTime.of(21, 59)))
    }

    /**
     * «С 22:00 до 22:00» — это опечатка пользователя, а не «молчать сутки».
     * Приложение, замолчавшее навсегда, разбирается дороже всего.
     */
    @Test
    fun `equal bounds mean an empty interval, not the whole day`() {
        val quiet = QuietHours(enabled = true, fromMinuteOfDay = 22 * 60, toMinuteOfDay = 22 * 60)

        assertFalse(quiet.isQuiet(LocalTime.of(22, 0)))
        assertFalse(quiet.isQuiet(LocalTime.of(3, 0)))
    }

    /** Испорченное значение в хранилище заворачивается по кругу, а не обрезается. */
    @Test
    fun `out of range bounds wrap around the day`() {
        assertEquals(0, (24 * 60).normalizedMinuteOfDay())
        assertEquals(23 * 60, (-60).normalizedMinuteOfDay())
        assertEquals(60, (25 * 60).normalizedMinuteOfDay())

        val quiet = QuietHours(enabled = true, fromMinuteOfDay = -120, toMinuteOfDay = 24 * 60)
        // -120 → 22:00, 24*60 → 00:00: тот же интервал «с 22 до полуночи».
        assertTrue(quiet.isQuiet(LocalTime.of(23, 0)))
        assertFalse(quiet.isQuiet(LocalTime.of(1, 0)))
    }

    @Test
    fun `minutes are formatted as a plain 24 hour clock`() {
        assertEquals("00:00", 0.formatMinuteOfDay())
        assertEquals("08:00", (8 * 60).formatMinuteOfDay())
        assertEquals("22:30", (22 * 60 + 30).formatMinuteOfDay())
    }

    /**
     * Хранятся выключенные категории: категория, которой в наборе нет, включена.
     * Обратное решение оставило бы новую категорию молчащей после обновления.
     */
    @Test
    fun `categories are enabled unless muted`() {
        val settings = NotificationSettings(
            mutedCategories = setOf(NotificationCategory.Marketing),
        )

        assertFalse(settings.isEnabled(NotificationCategory.Marketing))
        assertTrue(settings.isEnabled(NotificationCategory.Orders))
        assertTrue(NotificationSettings().isEnabled(NotificationCategory.Marketing))
    }
}
