package uz.mahalla.feature.freelancer.ui.orders

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.FreelancerOrder

/**
 * Входящие заказы мастера (issue #190): то, что клиенты заказали у этого
 * мастера, а не то, что он сам заказал ([MyFreelancerOrdersState]).
 *
 * В отличие от «Моих заказов у мастеров» список здесь не только читается:
 * мастер принимает, отклоняет или отмечает заказ выполненным
 * (`PUT freelancers/orders/{orderId}/status`, контракт не сверен со стендом —
 * `docs/API-CONTRACT.md`).
 *
 * @param pendingOrderIds заказы, для которых сейчас летит смена статуса.
 * Набор, а не одно значение: заказы независимы друг от друга, и пока один
 * ждёт ответа сервера, кнопки остальных карточек должны оставаться
 * кликабельными — блокируется только повторный клик по **тому же** заказу
 * (то же правило, что у `deletingServiceId` в
 * [uz.mahalla.feature.freelancer.ui.me.MyServicesState], но там на экране
 * может действовать только один заказ, а на этом их — целый список).
 * @param actionFailure отказ последней смены статуса. Отдельно от
 * [ScreenState.Error] списка: список мог загрузиться успешно, а действие —
 * нет, и тогда список показывать не перестаём. Очищается сам, как только
 * начинается новое действие (то же правило, что у `serviceFailure` в
 * [uz.mahalla.feature.freelancer.ui.me.MyServicesState]) — иначе прежний
 * отказ висел бы баннером над уже успешной попыткой.
 */
data class MyFreelancerIncomingOrdersState(
    val orders: ScreenState<List<FreelancerOrder>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val pendingOrderIds: Set<String> = emptySet(),
    val actionFailure: ApiFailure? = null,
) : UiState

sealed interface MyFreelancerIncomingOrdersEvent : UiEvent {
    /**
     * Экран вернулся на передний план: новый заказ мог прийти, пока
     * приложение было в фоне, — а увидеть именно это сюда и приходят.
     */
    data object ScreenResumed : MyFreelancerIncomingOrdersEvent

    data object Refreshed : MyFreelancerIncomingOrdersEvent
    data object Retry : MyFreelancerIncomingOrdersEvent
    data object LoadMore : MyFreelancerIncomingOrdersEvent

    data class AcceptClicked(val orderId: String) : MyFreelancerIncomingOrdersEvent
    data class RejectClicked(val orderId: String) : MyFreelancerIncomingOrdersEvent
    data class CompleteClicked(val orderId: String) : MyFreelancerIncomingOrdersEvent
}

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface MyFreelancerIncomingOrdersEffect : UiEffect
