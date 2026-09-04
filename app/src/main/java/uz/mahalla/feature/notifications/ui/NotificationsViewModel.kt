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

    /**
     * Уведомления, отметку которых сервер ещё не подтвердил. Второй тап по той
     * же строке нового запроса не заводит: на экране она уже прочитана, а
     * гасить прочитанное дважды незачем.
     */
    private val pendingRead = mutableSetOf<String>()

    /**
     * Сколько раз состояние «прочитано» переписывалось поверх (перезагрузка
     * списка или успешное «прочитать всё»). Нужен для откатов: пришедший
     * позже отказ одиночной отметки не должен возвращать в непрочитанные то,
     * о чём сервер уже сказал своё слово, — иначе бейдж уехал бы вверх на
     * пустом месте.
     */
    private var readEpoch = 0

    /** Что повторить по [NotificationsEvent.RetryAction]. */
    private var failedAction: NotificationsEvent? = null

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
            NotificationsEvent.RetryAction -> retryAction()
            is NotificationsEvent.NotificationClicked -> open(event.id)
        }
    }

    /**
     * Повтор отказавшего действия. Отказ без запомненного действия повторять
     * нечем — строка отказа просто останется на экране, а не превратится в
     * кнопку, которая ничего не делает.
     */
    private fun retryAction() {
        when (val action = failedAction) {
            NotificationsEvent.MarkAllRead -> markAllRead()
            is NotificationsEvent.NotificationClicked -> markRead(action.id)
            else -> Unit
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        // Дальше «прочитано» приезжает с сервера: откатывать его по чужому
        // отказу больше нельзя.
        readEpoch++
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
     * «Прочитать всё» — единственный способ погасить непрочитанное на
     * страницах, которые ещё не загружены.
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
                is ApiResult.Failure -> {
                    failedAction = NotificationsEvent.MarkAllRead
                    updateState { copy(isMarkingRead = false, actionFailure = result.failure) }
                }

                is ApiResult.Success -> {
                    readEpoch++
                    updateState {
                        copy(
                            items = items.withRead(isRead = true),
                            unreadCount = 0,
                            isMarkingRead = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Открытое уведомление становится прочитанным (issue #95) — и то, что
     * ведёт на экран, и то, что остаётся текстом в списке: человек его уже
     * прочёл, а гасить каждое кнопкой «прочитать всё» значит терять остальные
     * непрочитанные заодно.
     *
     * Отметка на месте, без перезапроса: перезагрузка сбросила бы догруженный
     * хвост списка к первой странице (то же правило, что у [markAllRead]).
     * Ответа сервера экран не ждёт — переход и перекраска происходят сразу,
     * иначе уведомление, по которому только что ушли, встречало бы человека
     * на обратном пути всё ещё непрочитанным.
     *
     * Отказ **виден**: состояние возвращается как было и показывается текст
     * бэкенда (issue #34). Молча перекрасить обратно значило бы соврать про
     * бейдж, который на следующем запросе всё равно приедет с сервера.
     */
    private fun markRead(id: String) {
        if (!pendingRead.add(id)) return
        val epoch = readEpoch
        updateState {
            copy(
                items = items.withRead(id = id, isRead = true),
                unreadCount = (unreadCount - 1).coerceAtLeast(0),
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.markRead(id)
            pendingRead -= id
            if (result is ApiResult.Failure) {
                failedAction = NotificationsEvent.NotificationClicked(id)
                updateState {
                    if (epoch == readEpoch) {
                        copy(
                            items = items.withRead(id = id, isRead = false),
                            unreadCount = unreadCount + 1,
                            actionFailure = result.failure,
                        )
                    } else {
                        copy(actionFailure = result.failure)
                    }
                }
            }
        }
    }

    /**
     * Переход по уведомлению. Цель разбирает [NotificationTarget]: для типов,
     * у которых экрана ещё нет (очередь, бронь, акции, подписки), эффекта нет
     * вовсе — такое уведомление просто гасится.
     */
    private fun open(id: String) {
        val notification = (currentState.items as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == id }
            ?: return
        if (!notification.isRead) markRead(id)
        when (val target = NotificationTarget.of(notification)) {
            is NotificationTarget.Order -> emitEffect(NotificationsEffect.OpenOrder(target.orderId))
            NotificationTarget.None -> Unit
        }
    }
}

/**
 * Перекраска «прочитано» в загруженных страницах. `id == null` — все
 * уведомления списка; состояние, отличное от [ScreenState.Content],
 * остаётся как есть (гасить нечего).
 */
private fun ScreenState<List<AppNotification>>.withRead(
    id: String? = null,
    isRead: Boolean,
): ScreenState<List<AppNotification>> =
    (this as? ScreenState.Content)?.let { content ->
        ScreenState.Content(
            content.data.map { item ->
                if (id == null || item.id == id) item.copy(isRead = isRead) else item
            },
        )
    } ?: this
