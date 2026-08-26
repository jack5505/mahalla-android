package uz.mahalla.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Расстояние до места (эпик 4).
 *
 * Форматтер отдаёт только число: единица измерения — строка из ресурсов,
 * иначе «m» и «km» пришлось бы держать в коде и они не переводились бы.
 * Ближе километра округляем до десятков метров — точность до метра в выдаче
 * ничего не даёт и создаёт ложное ощущение измерения.
 */
object DistanceFormatter {

    const val METERS_IN_KILOMETER = 1000

    /** Дальше этого расстояния показываем километры. */
    fun isKilometers(meters: Int): Boolean = meters >= METERS_IN_KILOMETER

    /** `450` → `450`; `1240` → `1,2`; `12400` → `12`. */
    fun value(meters: Int): String {
        val safe = meters.coerceAtLeast(0)
        if (!isKilometers(safe)) return roundToTens(safe).toString()

        val kilometers = safe.toDouble() / METERS_IN_KILOMETER
        // От десяти километров дробь уже не информативна.
        if (kilometers >= WHOLE_KILOMETERS_FROM) return kilometers.toInt().toString()
        return oneDecimal(kilometers)
    }

    private fun roundToTens(meters: Int): Int =
        if (meters < TENS_STEP) meters else (meters / TENS_STEP) * TENS_STEP

    private const val TENS_STEP = 10
    private const val WHOLE_KILOMETERS_FROM = 10.0
}

/**
 * Рейтинг: всегда один знак после запятой (`4,0`, а не `4`), иначе строки в
 * списке разной длины и колонка «прыгает».
 */
object RatingFormatter {

    /** `null`, если оценок нет: `0,0` читается как очень плохой рейтинг. */
    fun format(rating: Double, reviewCount: Int = 1): String? {
        if (rating <= 0.0 || reviewCount <= 0) return null
        return oneDecimal(rating)
    }

    /** Число отзывов в подписи: те же разряды, что у сумм. */
    fun reviewCount(count: Int): String = MoneyFormatter.amount(count.coerceAtLeast(0).toLong())
}

/**
 * Один знак после запятой с десятичной запятой. Запятая задана явно, а не
 * взята из системной локали: иначе вывод менялся бы вместе с языком телефона
 * и переставал совпадать с макетом.
 *
 * `BigDecimal.valueOf` (а не конструктор от `Double`) — чтобы округлялось
 * десятичное представление числа, а не его двоичный «хвост».
 */
private fun oneDecimal(value: Double): String = BigDecimal.valueOf(value)
    .setScale(1, RoundingMode.HALF_UP)
    .toPlainString()
    .replace('.', ',')
