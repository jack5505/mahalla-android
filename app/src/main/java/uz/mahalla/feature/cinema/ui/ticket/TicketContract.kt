package uz.mahalla.feature.cinema.ui.ticket

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaTicket

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface TicketEffect : UiEffect

/**
 * Карточка билета по id (issue #183): своего экрана у билета из «моих
 * активностей» и «моих билетов» раньше не было — открывался снимок из списка,
 * который сеанс кинотеатра успевал устареть (`ACTIVE` → `USED` на входе).
 */
data class TicketState(
    val ticket: ScreenState<CinemaTicket> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
) : UiState

sealed interface TicketEvent : UiEvent {
    /** Билет мог быть погашен на входе, пока приложение было в фоне. */
    data object ScreenResumed : TicketEvent

    data object Refreshed : TicketEvent
    data object Retry : TicketEvent
}
