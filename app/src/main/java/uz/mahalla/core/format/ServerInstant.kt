package uz.mahalla.core.format

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * **Отметка события** из ответа бэкенда: `createdAt`, `updatedAt`, время
 * покупки билета, время входа с устройства.
 *
 * Jackson на бэкенде сериализует `LocalDateTime` без зоны
 * (`2026-08-29T16:09:06.688`), а `Instant` — с `Z`; принимаем оба, второй
 * считая временем UTC. Иначе дата пуста у всех — так было у отзывов
 * (issue #53), и так же выглядел бы список устройств (issue #61).
 *
 * Зоне-менее строка здесь читается как **UTC**: отметку ставит сам сервер
 * своими часами, а он живёт в UTC — его же `Instant`-поля приезжают с `Z`.
 *
 * Для **времени слота** этого мало: 13:00 в брони человек выбрал по часам на
 * стене, а не по UTC. Такое время разбирает [parseServerSlotInstant] — путать
 * их нельзя, разница ровно пять часов (issue #144).
 *
 * Разбор мягкий: неразобранное значение — это `null`, а не исключение. Битое
 * поле в одной записи не должно ронять весь список.
 */
fun parseServerInstant(value: String?): Instant? =
    parseServerInstant(value, naiveZone = ZoneOffset.UTC)

/**
 * **Время слота** из ответа бэкенда: начало и конец брони игровой зоны.
 *
 * От [parseServerInstant] отличается одним — трактовкой зоне-менее строки.
 * Слот — не отметка сервера, а выбор человека в местных часах заведения,
 * поэтому `2026-09-05T13:00:00` — это 13:00 **в Ташкенте**
 * ([DateTimeFormatters.AppZone]), как и `apptDate` + `startTime` у записи к
 * мастеру. Читать его как UTC значило бы показать бронь на пять часов позже
 * выбранной и поставить её не туда в порядке «ближайшее сверху» — расхождение
 * из issue #144.
 *
 * Обратная сторона того же решения — `gamingRequestTime` в
 * `feature/gaming/data/GamingMappers.kt`: слот **уходит** на сервер в той же
 * зоне, в которой читается. Менять трактовку можно только вместе с ним, иначе
 * показ и отправка разъедутся.
 *
 * Строка с `Z` разбирается как есть, обеими функциями одинаково: начнёт
 * бэкенд отдавать слоты моментом — гадать будет не о чем. Явное смещение
 * (`+05:00`) тоже разбирается как есть, своей зоной, а не
 * [DateTimeFormatters.AppZone] — это сильнее предположения о местных часах,
 * раз сервер сам назвал зону.
 */
fun parseServerSlotInstant(value: String?): Instant? =
    parseServerInstant(value, naiveZone = DateTimeFormatters.AppZone)

/**
 * `Instant.parse` на minSdk 26 — это Java-8 `ISO_INSTANT`, который берёт
 * только хвостовой `Z`. Строку со смещением (`+05:00`, как у Ташкента) или с
 * именем зоны в скобках (`[Asia/Tashkent]`) он молча не разбирает — не
 * исключение, а `DateTimeParseException` уже на входе, так что без явного
 * `OffsetDateTime`/`ZonedDateTime` шага такая строка проваливалась сразу в
 * наивный разбор и трактовалась как [naiveZone], хотя зона была известна
 * (issue #176). Наивный `LocalDateTime` — последний шаг именно поэтому: он
 * единственный, кто готов разобрать строку без какой-либо зоны вообще.
 */
private fun parseServerInstant(value: String?, naiveZone: ZoneId): Instant? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        Instant.parse(raw)
    } catch (invalid: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (invalidOffset: DateTimeParseException) {
            try {
                ZonedDateTime.parse(raw).toInstant()
            } catch (invalidZoned: DateTimeParseException) {
                try {
                    LocalDateTime.parse(raw).atZone(naiveZone).toInstant()
                } catch (invalidLocal: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

/**
 * День без времени из ответа бэкенда (`yyyy-MM-dd`): дата записи
 * (`apptDate`, issue #97), день сеанса и дата выхода фильма (issue #106).
 *
 * Разбор такой же мягкий: битая дата — `null`, а не исключение. Запись без
 * дня показывается как есть, теряться из списка ей незачем.
 */
fun parseServerLocalDate(value: String?): LocalDate? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        LocalDate.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}
