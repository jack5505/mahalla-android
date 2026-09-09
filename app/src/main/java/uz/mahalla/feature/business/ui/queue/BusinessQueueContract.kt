package uz.mahalla.feature.business.ui.queue

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueActionRules
import uz.mahalla.feature.business.domain.QueueEntry

/**
 * Состояние управления очередью (задача 12.2).
 *
 * @param pendingTicketId талон, по которому идёт запрос. Действия блокируются
 * точечно, а не всем экраном, но **остальные талоны на это время тоже
 * заперты**: очередь пересчитывается сервером целиком, и второе действие
 * ушло бы в список, которого уже нет.
 * @param actionFailure отказ действия. Отдельно от [entries]: очередь уже на
 * экране, и прятать её из-за неудавшейся кнопки незачем.
 */
data class BusinessQueueState(
    val placeName: String = "",
    val entries: ScreenState<List<QueueEntry>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val pendingTicketId: String? = null,
    val actionFailure: ApiFailure? = null,
) : UiState {

    private val loaded: List<QueueEntry>
        get() = (entries as? ScreenState.Content)?.data.orEmpty()

    /**
     * Кого вызывать следующим. Считает домен: правило «пока кто-то в кресле,
     * следующего нет» должно быть одним и тем же и в кнопке над списком, и в
     * строке талона.
     */
    val nextInLine: QueueEntry? get() = QueueActionRules.nextInLine(loaded)

    /** Сколько человек ещё ждёт: подпись под кнопкой «вызвать следующего». */
    val waitingCount: Int get() = loaded.count { !QueueActionRules.isFinished(it.status) }

    /** Экран занят запросом — новые действия не принимаются. */
    val isBusy: Boolean get() = pendingTicketId != null
}

sealed interface BusinessQueueEvent : UiEvent {
    /** Очередь двигают другие мастера и сами клиенты — на возврате перечитываем. */
    data object ScreenResumed : BusinessQueueEvent

    data object Refreshed : BusinessQueueEvent
    data object Retry : BusinessQueueEvent

    data class ActionClicked(val ticketId: String, val action: QueueAction) : BusinessQueueEvent

    /** Кнопка над списком: вызвать того, кто ближе всех к креслу. */
    data object CallNextClicked : BusinessQueueEvent
}

sealed interface BusinessQueueEffect : UiEffect {
    /**
     * Действие выполнено — экран говорит об этом snackbar'ом.
     *
     * Едет [QueueAction] и имя человека, а не готовая строка: подписи живут в
     * ресурсах, и собрать их можно только в Compose (правило `i18n.md`).
     */
    data class ActionDone(val action: QueueAction, val userName: String) : BusinessQueueEffect
}
