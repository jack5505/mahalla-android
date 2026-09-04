package uz.mahalla.feature.queue.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInRequestError
import uz.mahalla.feature.queue.domain.WalkInStatusFlow
import uz.mahalla.feature.queue.domain.WalkInTicket

/**
 * Состояние экрана очереди (issue #96). Один экран на два вида: пока талона
 * нет — форма записи, когда есть — сам талон.
 *
 * @param isLoading читается локальный талон. Отдельно от [ticket], потому что
 * «талона нет» и «ещё не знаем» — разные вещи: во втором случае форму
 * показывать нельзя, иначе человек начнёт записываться второй раз.
 * @param validationShown причины показываются только после первой попытки
 * отправки: подсвечивать пустое поле сразу — ругать за то, что человек ещё не
 * начал.
 * @param queueInfoIsCurrent позиция и время ожидания ещё свежие. Считается от
 * `Clock` при загрузке и на каждом возврате на экран: перечитать очередь
 * нечем, и число из прошлого часа выдавать за текущее нельзя.
 * @param submitFailure отказ записи — вместе с ответом сервера (issue #34).
 * Набранное при этом остаётся: терять имя и услугу из-за отказа незачем.
 */
data class QueueState(
    val placeName: String = "",
    val request: WalkInRequest = WalkInRequest(placeId = ""),
    val errors: List<WalkInRequestError> = emptyList(),
    val validationShown: Boolean = false,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val submitFailure: ApiFailure? = null,
    val ticket: WalkInTicket? = null,
    val queueInfoIsCurrent: Boolean = false,
    val cancelConfirmVisible: Boolean = false,
    val isCancelling: Boolean = false,
    val cancelFailure: ApiFailure? = null,
) : UiState {

    val canSubmit: Boolean get() = errors.isEmpty() && !isSubmitting

    /** Отмену показываем и пока она идёт: кнопка не должна исчезать под пальцем. */
    val canCancel: Boolean
        get() = ticket != null && WalkInStatusFlow.canCancel(ticket.status)
}

sealed interface QueueEvent : UiEvent {
    data class NameChanged(val name: String) : QueueEvent
    data class ServiceChanged(val service: String) : QueueEvent
    data object SubmitClicked : QueueEvent

    data object CancelClicked : QueueEvent
    data object CancelDismissed : QueueEvent
    data object CancelConfirmed : QueueEvent

    /**
     * Экран вернулся на передний план. Опроса статуса здесь нет — опрашивать
     * нечем (см. `WalkInApi`), — но свежесть показанных чисел пересчитывается:
     * пока приложение было в фоне, позиция могла устареть.
     */
    data object ScreenResumed : QueueEvent

    /**
     * «Открыть уведомления»: о решении мастера бэкенд сообщает уведомлениями
     * (`WALKIN_ACCEPTED`, `WALKIN_DECLINED`, `WALKIN_COMPLETE`), и это
     * единственный источник, где состояние талона обновляет сам сервер.
     */
    data object NotificationsClicked : QueueEvent

    data object BackClicked : QueueEvent
}

sealed interface QueueEffect : UiEffect {
    data object OpenNotifications : QueueEffect
    data object NavigateBack : QueueEffect
}
