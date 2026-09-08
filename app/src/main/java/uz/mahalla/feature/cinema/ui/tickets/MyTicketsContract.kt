package uz.mahalla.feature.cinema.ui.tickets

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaTicket

/**
 * Состояние экрана «Мои билеты» (issue #106).
 *
 * Разделов «предстоящие» и «прошедшие», как у записей (issue #97), здесь
 * **нет**: времени сеанса в `CinemaTicket` не бывает — только `sessionId`, — и
 * разложить билеты по дням приложению не на чем. Порядок задаёт домен
 * ([uz.mahalla.feature.cinema.domain.CinemaTickets]): действующие сверху.
 *
 * @param confirmCancel билет, возврат которого человек подтверждает.
 * Хранится целиком, а не одним id: диалог называет место и цену, а искать их
 * в списке ради подписи — лишний повод разойтись с тем, что нажали.
 * @param pendingCancelId строка, по которой сейчас идёт возврат: пока она
 * висит, остальные тоже не трогаем — ответы приезжали бы на список, которого
 * уже нет (то же правило, что у устройств в профиле, issue #61).
 * @param cancelFailure отказ возврата — отдельно от [tickets]: список уже на
 * экране, и прятать его из-за неудавшейся кнопки незачем.
 */
data class MyTicketsState(
    val tickets: ScreenState<List<CinemaTicket>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val confirmCancel: CinemaTicket? = null,
    val pendingCancelId: String? = null,
    val cancelFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface MyTicketsEvent : UiEvent {
    /**
     * Экран вернулся на передний план: кинотеатр мог отметить билет
     * использованным или вернуть его, пока приложение было в фоне.
     */
    data object ScreenResumed : MyTicketsEvent

    data object Refreshed : MyTicketsEvent
    data object Retry : MyTicketsEvent
    data object LoadMore : MyTicketsEvent

    data class CancelRequested(val ticketId: String) : MyTicketsEvent
    data object CancelDismissed : MyTicketsEvent
    data object CancelConfirmed : MyTicketsEvent
}
