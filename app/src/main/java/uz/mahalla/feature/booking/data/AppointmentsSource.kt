package uz.mahalla.feature.booking.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage

/**
 * Откуда экран «мои записи» берёт список и куда отправляет отмену.
 *
 * Вертикалей записи у бэкенда две — к мастеру (`appointments`, issue #97) и к
 * врачу (`hospitals/appointments`, issue #99), — но **модель у них одна**: обе
 * ручки `my` отдают `PageResponseAppointmentResponse`, а отменяет обе один и
 * тот же `POST appointments/{id}/cancel` (своей отмены у `hospitals` нет
 * вовсе). Значит и экран у них может быть один: он отличается заголовком, а не
 * поведением.
 *
 * Интерфейс существует ровно ради этого — чтобы
 * [uz.mahalla.feature.booking.ui.appointments.MyAppointmentsViewModel] выбирал
 * источник по аргументу маршрута, а не чтобы завести вторую копию экрана,
 * которая разошлась бы с первой при первой же правке (то же решение, что у
 * `RoleRoute(onboarding)` в issue #84).
 */
interface AppointmentsSource {

    /** Свои записи, страницами. */
    suspend fun myAppointments(
        page: Int = 0,
        size: Int = BookingRepository.PAGE_SIZE,
    ): ApiResult<AppointmentPage>

    /**
     * Отменить свою запись. Возвращается состояние после отмены — либо из
     * ответа сервера, либо выведенное из самого факта успешной отмены.
     */
    suspend fun cancel(appointment: Appointment): ApiResult<Appointment>
}
