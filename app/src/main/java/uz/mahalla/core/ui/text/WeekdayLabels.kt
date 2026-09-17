package uz.mahalla.core.ui.text

import androidx.annotation.StringRes
import uz.mahalla.R
import java.time.DayOfWeek

/**
 * Полное название дня недели из ресурсов, а не из `DayOfWeek.getDisplayName`:
 * там имя зависит от локали устройства, а язык приложение выбирает своё, и
 * узбекский интерфейс на русском телефоне показывал бы русские дни.
 *
 * Один список на все экраны (часы работы места, мета шапки главной).
 */
@StringRes
fun DayOfWeek.fullLabelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.day_monday
    DayOfWeek.TUESDAY -> R.string.day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.day_wednesday
    DayOfWeek.THURSDAY -> R.string.day_thursday
    DayOfWeek.FRIDAY -> R.string.day_friday
    DayOfWeek.SATURDAY -> R.string.day_saturday
    DayOfWeek.SUNDAY -> R.string.day_sunday
}
