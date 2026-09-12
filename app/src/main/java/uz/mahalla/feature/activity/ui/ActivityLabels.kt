package uz.mahalla.feature.activity.ui

import androidx.annotation.StringRes
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus

/**
 * Подписи «моих активностей» (issue #73).
 *
 * Вынесены из композабла отдельными функциями: `when` по трём перечислениям
 * внутри разметки не проверить тестом, а забытая ветка там — это пустая
 * строка на экране (в Kotlin `when` по enum со `else` компилятор не заставляет
 * дописывать). Здесь ветки исчерпывающие, и новое значение перечисления ломает
 * сборку — то есть о нём узнают до релиза, а не после.
 */

@StringRes
internal fun ActivityKind.labelRes(): Int = when (this) {
    ActivityKind.FoodOrder -> R.string.activity_kind_food_order
    ActivityKind.ClothingOrder -> R.string.activity_kind_clothing_order
    ActivityKind.PharmacyOrder -> R.string.activity_kind_pharmacy_order
    ActivityKind.CinemaOrder -> R.string.activity_kind_cinema_order
    ActivityKind.GamingOrder -> R.string.activity_kind_gaming_order
    ActivityKind.OtherOrder -> R.string.activity_kind_other_order
    ActivityKind.GamingBooking -> R.string.activity_kind_gaming_booking
    ActivityKind.MasterAppointment -> R.string.activity_kind_master_appointment
    ActivityKind.DoctorAppointment -> R.string.activity_kind_doctor_appointment
    ActivityKind.CinemaTicket -> R.string.activity_kind_cinema_ticket
}

@StringRes
internal fun ActivityStatus.labelRes(): Int = when (this) {
    ActivityStatus.Placed -> R.string.activity_status_placed
    ActivityStatus.Confirmed -> R.string.activity_status_confirmed
    ActivityStatus.InProgress -> R.string.activity_status_in_progress
    ActivityStatus.Ready -> R.string.activity_status_ready
    ActivityStatus.OnTheWay -> R.string.activity_status_on_the_way
    ActivityStatus.Completed -> R.string.activity_status_completed
    ActivityStatus.Cancelled -> R.string.activity_status_cancelled
    ActivityStatus.Refunded -> R.string.activity_status_refunded
    ActivityStatus.Missed -> R.string.activity_status_missed
    ActivityStatus.Unknown -> R.string.activity_status_unknown
}

/**
 * Цвет бейджа статуса.
 *
 * Отмена и «не пришли» — не ошибка приложения, поэтому [MahallaTone.Neutral], а
 * не `Error`: красный бейдж в списке читается как «что-то сломалось», хотя
 * заказ отменил сам человек. `Error` в ките остаётся за настоящими отказами.
 * Возврат денег — [MahallaTone.Warning]: это состояние, за которым стоит
 * следить.
 */
internal fun ActivityStatus.tone(): MahallaTone = when (this) {
    ActivityStatus.Placed, ActivityStatus.Confirmed -> MahallaTone.Info
    ActivityStatus.InProgress, ActivityStatus.OnTheWay, ActivityStatus.Unknown -> MahallaTone.Accent
    ActivityStatus.Ready, ActivityStatus.Completed -> MahallaTone.Success
    ActivityStatus.Refunded -> MahallaTone.Warning
    ActivityStatus.Cancelled, ActivityStatus.Missed -> MahallaTone.Neutral
}

/** Название сбойного раздела для отметки частичного отказа. */
@StringRes
internal fun ActivitySource.labelRes(): Int = when (this) {
    ActivitySource.Orders -> R.string.activity_source_orders
    ActivitySource.GamingBookings -> R.string.activity_source_gaming_bookings
    ActivitySource.MasterAppointments -> R.string.activity_source_master_appointments
    ActivitySource.DoctorAppointments -> R.string.activity_source_doctor_appointments
    ActivitySource.CinemaTickets -> R.string.activity_source_cinema_tickets
}
