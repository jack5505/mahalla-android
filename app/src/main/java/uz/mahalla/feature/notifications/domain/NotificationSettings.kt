package uz.mahalla.feature.notifications.domain

import java.time.LocalTime

/**
 * Тихие часы (эпик 11): интервал суток, в который пуш не звучит.
 *
 * Границы — минуты от полуночи, а не `LocalTime`: значение хранится в
 * DataStore числом, а разбирать строку времени при каждом пуше значит зависеть
 * от локали в коде, который работает в фоне.
 *
 * Интервал **может проходить через полночь** — собственно, ради этого случая он
 * и нужен: «с 22:00 до 08:00» это обычная настройка, а «с 22:00 до 8:00
 * следующего дня» пользователь набирать не должен.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val fromMinuteOfDay: Int = DEFAULT_FROM,
    val toMinuteOfDay: Int = DEFAULT_TO,
) {

    /** Минуты нормализованы: значение из хранилища может быть любым. */
    val from: Int get() = fromMinuteOfDay.normalizedMinuteOfDay()
    val to: Int get() = toMinuteOfDay.normalizedMinuteOfDay()

    /**
     * Молчать ли сейчас.
     *
     * Совпадение границ — это **не** «сутки напролёт»: «с 22:00 до 22:00»
     * человек набирает по ошибке, а результатом было бы приложение, которое
     * молчит всегда, — самая дорогая для разбора жалоба. Считаем такой интервал
     * пустым.
     */
    fun isQuiet(at: LocalTime): Boolean {
        if (!enabled) return false
        val minute = at.hour * MINUTES_IN_HOUR + at.minute
        val start = from
        val end = to
        return when {
            start == end -> false
            // Обычный интервал внутри суток: 13:00 → 15:00.
            start < end -> minute >= start && minute < end
            // Через полночь: 22:00 → 08:00.
            else -> minute >= start || minute < end
        }
    }

    companion object {
        const val MINUTES_IN_HOUR = 60
        const val MINUTES_IN_DAY = 24 * MINUTES_IN_HOUR

        /** 22:00 — на нём же стоит «не беспокоить» самой системы. */
        const val DEFAULT_FROM = 22 * MINUTES_IN_HOUR

        /** 08:00. */
        const val DEFAULT_TO = 8 * MINUTES_IN_HOUR
    }
}

/**
 * Минута суток из любого числа: отрицательные и вышедшие за сутки значения
 * заворачиваются, а не обрезаются. Испорченный ключ хранилища не должен
 * превращаться в полночь — по кругу он хотя бы остаётся тем же временем.
 */
fun Int.normalizedMinuteOfDay(): Int =
    Math.floorMod(this, QuietHours.MINUTES_IN_DAY)

/** «22:00» — время без локали: и uz, и ru пишут его одинаково. */
fun Int.formatMinuteOfDay(): String {
    val minute = normalizedMinuteOfDay()
    return "%02d:%02d".format(
        minute / QuietHours.MINUTES_IN_HOUR,
        minute % QuietHours.MINUTES_IN_HOUR,
    )
}

/**
 * Настройки уведомлений (эпик 11) — **локальные**.
 *
 * Серверных настроек уведомлений у бэкенда нет (сверено по `/v3/api-docs`
 * 2026-09-09): ни категорий, ни тихих часов, ни ручки, куда их сохранить.
 * Поэтому фильтр стоит на устройстве: пуш приходит, а показывать его или нет,
 * решает приложение. Плата за это известна — на втором устройстве настройки
 * свои; появится ручка на бэкенде, настройки переедут туда.
 *
 * @param mutedCategories выключенные категории. Хранится именно выключенное, а
 * не включённое: новая категория тогда по умолчанию включена, а не потеряна —
 * ровно то, чего человек ждёт от приложения, которое обновилось.
 */
data class NotificationSettings(
    val mutedCategories: Set<NotificationCategory> = emptySet(),
    val quietHours: QuietHours = QuietHours(),
) {

    fun isEnabled(category: NotificationCategory): Boolean = category !in mutedCategories
}
