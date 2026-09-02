package uz.mahalla.feature.notifications.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.notifications.data.NotificationsRepository
import uz.mahalla.feature.notifications.domain.AppNotification
import uz.mahalla.feature.notifications.domain.NotificationPage
import uz.mahalla.feature.notifications.domain.NotificationTarget
import javax.inject.Inject

/**
 * Центр уведомлений (issue #81, задача T11).
 *
 * Список и счётчик непрочитанного — две разные ручки, и запрашиваются они
 * параллельно: последовательный запрос удвоил бы время до первого экрана без
 * всякой причины. Отказ счётчика при этом список не роняет — он влияет только
 * на кнопку «прочитать всё».
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
) : MviViewModel<NotificationsState, NotificationsEvent, NotificationsEffect>(
    NotificationsState(),
) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: NotificationsEvent) {
        when (event) {
            // Возврат на экран: уведомление могло прийти, пока приложение было
            // в фоне. Пока идёт загрузка, перезапрашивать нечего — ответ
            // приедет на уже сменившееся состояние.
            NotificationsEvent.ScreenResumed ->
                if (!currentState.items.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            NotificationsEvent.Refreshed -> load(showLoading = false, refreshing = true)
            NotificationsEvent.Retry -> load()
            NotificationsEvent.LoadMore -> loadMore()
            NotificationsEvent.MarkAllRead -> markAllRead()
            is NotificationsEvent.NotificationClicked -> open(event.id)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                items = if (showLoading) ScreenState.Loading else items,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            val page = async { repository.notifications(page = 0) }
            val unread = async { repository.unreadCount() }
            applyPage(page.await())
            // Счётчик не обязан доехать: без него просто не появится кнопка
            // «прочитать всё» — прежнее значение при этом остаётся, иначе
            // кнопка мигала бы на каждом возврате при плохой сети.
            (unread.await() as? ApiResult.Success)?.let { result ->
                updateState { copy(unreadCount = result.data) }
            }
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<NotificationPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(items = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    items = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Догрузка страницы. Провал не стирает уже показанные уведомления, но и
     * молча дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу
     * списка больше не сработает — поэтому хвост переходит в состояние
     * «повторить» вместе с причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.items as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.notifications(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            items = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Уведомление может приехать на двух соседних страницах, если список
     * пополнился между запросами. В `LazyColumn` это дубликат ключа и падение,
     * поэтому дедупликация по id обязательна.
     */
    private fun appended(
        current: List<AppNotification>,
        next: List<AppNotification>,
    ): List<AppNotification> {
        val known = current.mapTo(mutableSetOf(), AppNotification::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * «Прочитать всё». Отдельного эндпоинта «прочитать одно» у бэкенда нет,
     * поэтому это единственный способ погасить бейдж.
     *
     * Загруженные страницы помечаются прочитанными на месте, а не
     * перезапрашиваются: сервер уже подтвердил успех, а полная перезагрузка
     * сбросила бы догруженный хвост списка к первой странице.
     */
    private fun markAllRead() {
        if (!currentState.canMarkAllRead) return
        updateState { copy(isMarkingRead = true, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.markAllRead()) {
                is ApiResult.Failure -> updateState {
                    copy(isMarkingRead = false, actionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        items = (items as? ScreenState.Content)
                            ?.let { content ->
                                ScreenState.Content(content.data.map { it.copy(isRead = true) })
                            }
                            ?: items,
                        unreadCount = 0,
                        isMarkingRead = false,
                    )
                }
            }
        }
    }

    /**
     * Переход по уведомлению. Цель разбирает [NotificationTarget]: для типов,
     * у которых экрана ещё нет (очередь, бронь, акции, подписки), эффекта нет
     * вовсе — строка списка такого уведомления и не кликабельна.
     */
    private fun open(id: String) {
        val notification = (currentState.items as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == id }
            ?: return
        when (val target = NotificationTarget.of(notification)) {
            is NotificationTarget.Order -> emitEffect(NotificationsEffect.OpenOrder(target.orderId))
            NotificationTarget.None -> Unit
        }
    }
}
