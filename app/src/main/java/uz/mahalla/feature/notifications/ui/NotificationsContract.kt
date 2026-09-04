package uz.mahalla.feature.notifications.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.notifications.domain.AppNotification

/**
 * Состояние центра уведомлений (issue #81).
 *
 * @param unreadCount непрочитанные на **сервере**, а не в загруженных
 * страницах: кнопка «прочитать всё» должна быть видна и тогда, когда
 * непрочитанное лежит на второй странице.
 * @param actionFailure отказ отметки прочитанным — всего списка или одного
 * уведомления (issue #95). Отдельно от [items]: список уже на экране, и
 * прятать его из-за неудавшегося действия незачем — причина показывается
 * строкой над ним текстом бэкенда (issue #34).
 * @param loadMoreFailure догрузка страницы не удалась — вместе с причиной,
 * чтобы кнопка «повторить» не осталась без объяснения.
 */
data class NotificationsState(
    val items: ScreenState<List<AppNotification>> = ScreenState.Loading,
    val unreadCount: Int = 0,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isMarkingRead: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState {

    /** Кнопка «прочитать всё» нужна, только когда есть что читать. */
    val canMarkAllRead: Boolean get() = unreadCount > 0 && !isMarkingRead
}

sealed interface NotificationsEvent : UiEvent {
    /** Экран вернулся на передний план: уведомления могли прийти в фоне. */
    data object ScreenResumed : NotificationsEvent

    data object Refreshed : NotificationsEvent
    data object Retry : NotificationsEvent
    data object LoadMore : NotificationsEvent
    data object MarkAllRead : NotificationsEvent

    /**
     * Повтор неудавшейся отметки прочитанным. Какой именно — помнит ViewModel:
     * экран показывает одну строку отказа и не должен знать, отказали ему в
     * «прочитать всё» или в одном уведомлении.
     */
    data object RetryAction : NotificationsEvent

    data class NotificationClicked(val id: String) : NotificationsEvent
}

sealed interface NotificationsEffect : UiEffect {
    /** Статус заказа вертикали «Еда» — единственная цель, которую даёт контракт. */
    data class OpenOrder(val orderId: String) : NotificationsEffect
}
