package uz.mahalla.feature.business.ui.queue

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueActionRules
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.navigation.BusinessArgs
import javax.inject.Inject

/**
 * Управление очередью (задача 12.2): принять, вызвать, завершить, отказать.
 *
 * Доступ здесь **не перепроверяется**: на этот экран попадают только из
 * панели, которая уже подтвердила права, а второй обход `places/my` на каждом
 * открытии стоил бы лишнего запроса перед каждой очередью. Последнее слово всё
 * равно за сервером — его отказ экран покажет текстом (issue #34).
 */
@HiltViewModel
class BusinessQueueViewModel @Inject constructor(
    private val repository: BusinessRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessQueueState, BusinessQueueEvent, BusinessQueueEffect>(
    BusinessQueueState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
    ),
) {

    private val placeId: String = savedStateHandle.get<String>(BusinessArgs.PLACE_ID).orEmpty()

    /**
     * Текущая загрузка. Хранится, чтобы «повторить» поверх pull-to-refresh не
     * давало двух параллельных запросов: выигрывал бы ответивший последним, то
     * есть возможен откат к более старой очереди (нашло ревью).
     */
    private var loadJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: BusinessQueueEvent) {
        when (event) {
            BusinessQueueEvent.ScreenResumed ->
                if (!currentState.entries.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            BusinessQueueEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessQueueEvent.Retry -> load()
            is BusinessQueueEvent.ActionClicked -> act(event.ticketId, event.action)
            BusinessQueueEvent.CallNextClicked -> callNext()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        updateState {
            copy(
                entries = if (showLoading) ScreenState.Loading else entries,
                isRefreshing = refreshing,
                actionFailure = null,
            )
        }
        loadJob = viewModelScope.launch {
            when (val result = repository.queue(placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(entries = ScreenState.Error(result.failure), isRefreshing = false)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        entries = if (result.data.isEmpty()) {
                            ScreenState.Empty
                        } else {
                            ScreenState.Content(result.data)
                        },
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    /** «Вызвать следующего» — тот же [QueueAction.Start], но выбирает домен. */
    private fun callNext() {
        val next = currentState.nextInLine ?: return
        act(next.id, QueueAction.Start)
    }

    /**
     * Действие над талоном.
     *
     * Правило проверяется **до** запроса: экран и так рисует только доступные
     * кнопки, но событие может прийти на устаревшее состояние — например,
     * человек нажал «вызвать» ровно тогда, когда список перечитался и талон
     * уже отменили. Отправить такой запрос значит показать отказ сервера там,
     * где приложение всё знало само.
     *
     * Успех правит список **на месте**, а не перезагружает его: сервер вернул
     * талон целиком, а полная перезагрузка мигнула бы скелетоном посреди
     * работы с очередью. Позиции соседей при этом сервер пересчитал, и они
     * доедут на ближайшем обновлении — показать чужую позицию на секунду
     * устаревшей безопаснее, чем дёргать список на каждое нажатие.
     */
    private fun act(ticketId: String, action: QueueAction) {
        val state = currentState
        if (state.isBusy) return
        val entry = entryOrNull(ticketId) ?: return
        if (!QueueActionRules.isAllowed(entry.status, action)) return

        updateState { copy(pendingTicketId = ticketId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.act(placeId, ticketId, action)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingTicketId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            entries = replaced(result.data),
                            pendingTicketId = null,
                        )
                    }
                    emitEffect(BusinessQueueEffect.ActionDone(action, entry.userName))
                }
            }
        }
    }

    /**
     * Обновлённый талон встаёт на своё место в списке.
     *
     * Из списка он **не убирается**, даже став завершённым: экран сам решает,
     * куда его положить (в «обслуженные» внизу), а исчезнувшая строка сразу
     * после нажатия читается как «удалил не того».
     */
    private fun replaced(updated: QueueEntry): ScreenState<List<QueueEntry>> {
        val content = currentState.entries as? ScreenState.Content ?: return currentState.entries
        return ScreenState.Content(
            content.data.map { if (it.id == updated.id) updated else it },
        )
    }

    private fun entryOrNull(ticketId: String): QueueEntry? =
        (currentState.entries as? ScreenState.Content)?.data?.firstOrNull { it.id == ticketId }
}
