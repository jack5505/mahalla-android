package uz.mahalla.feature.business.domain

import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical

/**
 * Переходы статуса записи со стороны заведения (задача из issue #289) —
 * журнал барбершопа и клиники на день.
 *
 * Правило то же, что у заказов ([BusinessOrderStatusFlow]): вперёд ровно на
 * шаг, назад — никогда. Отдельный объект, а не переиспользование
 * [BusinessOrderStatusFlow]: домен другой (запись, не заказ), и набор
 * статусов у него свой ([AppointmentStatus]).
 */
object BusinessAppointmentStatusFlow {

    /**
     * Что можно выставить из текущего статуса.
     *
     * `PENDING` заведение либо подтверждает, либо отказывает — то же, что
     * «новый» заказ. Дальше решает вертикаль: у барбершопа есть «не пришёл»
     * (`AppointmentBookingResponse.status` знает `NO_SHOW`), у клиники —
     * нет (`HospitalAppointmentResponse` — только `PENDING`/`CONFIRMED`/
     * `COMPLETED`/`CANCELLED`). Предложить кнопку, которой нет в схеме,
     * значило бы получить отказ сервера там, где приложение могло знать
     * заранее.
     */
    fun nextStatuses(status: AppointmentStatus, vertical: AppointmentVertical): List<AppointmentStatus> =
        when (status) {
            AppointmentStatus.Pending ->
                listOf(AppointmentStatus.Confirmed, AppointmentStatus.Cancelled)

            AppointmentStatus.Confirmed -> when (vertical) {
                AppointmentVertical.Barber -> listOf(
                    AppointmentStatus.Completed,
                    AppointmentStatus.NoShow,
                    AppointmentStatus.Cancelled,
                )

                AppointmentVertical.Doctor -> listOf(
                    AppointmentStatus.Completed,
                    AppointmentStatus.Cancelled,
                )
            }

            AppointmentStatus.Completed, AppointmentStatus.Cancelled, AppointmentStatus.NoShow,
            AppointmentStatus.Unknown,
            -> emptyList()
        }

    fun isAllowed(from: AppointmentStatus, to: AppointmentStatus, vertical: AppointmentVertical): Boolean =
        to in nextStatuses(from, vertical)

    /** Дальше заведение с записью ничего не делает. */
    fun isFinal(status: AppointmentStatus): Boolean = status == AppointmentStatus.Completed ||
        status == AppointmentStatus.Cancelled ||
        status == AppointmentStatus.NoShow

    /** Ждёт ответа заведения — то же, что «новый» у заказов. */
    fun isPending(status: AppointmentStatus): Boolean = status == AppointmentStatus.Pending
}
