package uz.mahalla.feature.notifications.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.notifications.data.NotificationsRepository
import uz.mahalla.feature.notifications.push.NotificationsBadgeSignal
import javax.inject.Inject

/**
 * Сколько уведомлений не прочитано (issue #81). Отдельно от
 * [NotificationsViewModel]: бейдж живёт в топбаре главной, то есть на экране,
 * который про уведомления больше ничего не знает.
 */
data class NotificationsBadgeState(val unreadCount: Int = 0) : UiState {

    /** Бейдж рисуется только когда есть что показать. */
    val hasUnread: Boolean get() = unreadCount > 0
}

sealed interface NotificationsBadgeEvent : UiEvent {
    /**
     * Экран вернулся на передний план. Именно здесь бейдж и обновляется:
     * уведомление приходит, пока приложение в фоне, а «прочитать всё» гасит
     * счётчик на соседнем экране — старт ViewModel про оба случая не знает.
     */
    data object ScreenResumed : NotificationsBadgeEvent
}

sealed interface NotificationsBadgeEffect : UiEffect

@HiltViewModel
class NotificationsBadgeViewModel @Inject constructor(
    private val repository: NotificationsRepository,
    private val badgeSignal: NotificationsBadgeSignal,
) : MviViewModel<NotificationsBadgeState, NotificationsBadgeEvent, NotificationsBadgeEffect>(
    NotificationsBadgeState(),
) {

    private var job: Job? = null

    /**
     * Пуш пришёл, пока запрос из предыдущего [refresh] ещё летел — тот может
     * вернуть значение, устаревшее уже к моменту ответа. Флаг переживает до
     * конца текущего запроса и перезапускает чтение внутри того же `job`
     * (issue #429): второй параллельный запрос не нужен, нужен ещё один после.
     */
    private var pendingRefresh = false

    init {
        refresh()
        viewModelScope.launch {
            badgeSignal.pushReceived.collect { refresh() }
        }
    }

    override fun onEvent(event: NotificationsBadgeEvent) {
        when (event) {
            NotificationsBadgeEvent.ScreenResumed -> refresh()
        }
    }

    /**
     * Отказ бэкенда бейдж не трогает: показывать ошибку на иконке топбара
     * нечем и незачем, а обнулять счётчик из-за пропавшей сети значило бы
     * соврать, что непрочитанного нет.
     */
    private fun refresh() {
        if (job?.isActive == true) {
            pendingRefresh = true
            return
        }
        job = viewModelScope.launch {
            do {
                pendingRefresh = false
                val result = repository.unreadCount()
                if (result is ApiResult.Success) {
                    updateState { copy(unreadCount = result.data) }
                }
            } while (pendingRefresh)
        }
    }
}
