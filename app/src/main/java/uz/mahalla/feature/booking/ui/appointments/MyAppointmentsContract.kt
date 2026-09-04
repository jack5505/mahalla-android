package uz.mahalla.feature.booking.ui.appointments

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentSections
import uz.mahalla.feature.booking.domain.AppointmentVertical

/**
 * Состояние экрана «Мои записи» (issue #97; врачи — issue #99).
 *
 * @param vertical к мастеру или к врачу. Экран один на обе вертикали, и от
 * этого зависят только заголовок и подпись безымянной записи: у врача её
 * место занимает специальность, а не название услуги.
 *
 * @param appointments загруженные страницы целиком, без деления: разложить их
 * на «активные» и «прошедшие» может только домен и только относительно
 * текущего времени ([sections]) — записанное в состояние деление устарело бы
 * молча.
 * @param confirmCancel запись, отмену которой человек подтверждает. Хранится
 * целиком, а не одним id: диалог называет услугу и время, а искать их в списке
 * ради подписи — лишний повод разойтись с тем, что нажали.
 * @param pendingCancelId строка, по которой сейчас идёт отмена: пока она
 * висит, остальные тоже не трогаем — ответы приезжали бы на список, которого
 * уже нет (то же правило, что у устройств в профиле, issue #61).
 * @param cancelFailure отказ отмены — отдельно от [appointments]: список уже
 * на экране, и прятать его из-за неудавшейся кнопки незачем.
 */
data class MyAppointmentsState(
    val vertical: AppointmentVertical = AppointmentVertical.Barber,
    val appointments: ScreenState<List<Appointment>> = ScreenState.Loading,
    val sections: AppointmentSections.Split = AppointmentSections.Split(),
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val confirmCancel: Appointment? = null,
    val pendingCancelId: String? = null,
    val cancelFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface MyAppointmentsEvent : UiEvent {
    /**
     * Экран вернулся на передний план: заведение могло подтвердить или
     * отменить запись, пока приложение было в фоне, — а увидеть именно это
     * сюда и приходят. Заодно пересчитывается деление на «активные» и
     * «прошедшие»: время идёт и без запросов.
     */
    data object ScreenResumed : MyAppointmentsEvent

    data object Refreshed : MyAppointmentsEvent
    data object Retry : MyAppointmentsEvent
    data object LoadMore : MyAppointmentsEvent

    data class CancelRequested(val appointmentId: String) : MyAppointmentsEvent
    data object CancelDismissed : MyAppointmentsEvent
    data object CancelConfirmed : MyAppointmentsEvent
}
