package uz.mahalla.feature.activity.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivityMerge
import uz.mahalla.feature.activity.domain.ActivityTarget
import javax.inject.Inject

/**
 * «Мои активности» (issue #73, задача T7) — один список из пяти вертикалей.
 *
 * Главное правило экрана: **частичный отказ не роняет список**. Четыре
 * источника с данными и один с ошибкой — это список плюс отметка о сбойном
 * разделе, а не пустой экран с «Nimadir xato ketdi». В `ScreenState.Error`
 * экран уходит только тогда, когда не ответил ни один источник — так выглядит
 * истёкшая сессия и отсутствие сети, и показывать в этом случае «вы ещё
 * ничего не заказывали» значит врать.
 *
 * Фильтр «активные / история» сети не касается: обе вкладки собираются из уже
 * загруженного списка. «Активное» — это набор статусов, а `GET orders`
 * принимает в параметре `status` ровно один, то есть отфильтровать на сервере
 * всё равно не получилось бы.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: ActivityRepository,
) : MviViewModel<ActivityState, ActivityEvent, ActivityEffect>(ActivityState()) {

    private var loadMoreJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: ActivityEvent) {
        when (event) {
            // Возврат на экран: пока приложение было в фоне, заказ могли
            // собрать, а бронь — подтвердить. Во время загрузки перезапрашивать
            // нечего: ответ приедет на уже сменившееся состояние.
            ActivityEvent.ScreenResumed ->
                if (!currentState.items.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            ActivityEvent.Refreshed -> load(showLoading = false, refreshing = true)

            // «Повторить» приходит из двух мест: из полного отказа (там
            // показывать скелетон правильно — списка нет) и из отметки
            // сбойного раздела при частичном отказе. Во втором случае список
            // уже на экране, и заменять его скелетоном значит забрать у
            // человека то, что он читает, из-за раздела, который его,
            // возможно, вообще не интересует.
            ActivityEvent.Retry -> load(showLoading = currentState.items !is ScreenState.Content)
            ActivityEvent.LoadMore -> loadMore()
            is ActivityEvent.FilterSelected -> updateState { copy(filter = event.filter) }
            is ActivityEvent.ActivityClicked -> open(event.key)
            ActivityEvent.DiscoveryRequested -> emitEffect(ActivityEffect.OpenDiscovery)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        updateState {
            copy(
                items = if (showLoading) ScreenState.Loading else items,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            val feed = repository.feed(pages = ActivityFeed.FIRST_PAGES)
            updateState {
                copy(
                    items = when {
                        // Не ответил вообще никто — только это ошибка экрана.
                        // Причину берём у любого источника: при полном отказе
                        // она у всех одна и та же (401, таймаут, нет сети).
                        feed.isTotalFailure -> ScreenState.Error(feed.failures.values.first())
                        feed.items.isEmpty() -> ScreenState.Empty
                        else -> ScreenState.Content(feed.items)
                    },
                    sourceFailures = feed.failures,
                    nextPages = feed.nextPages,
                    isRefreshing = false,
                )
            }
        }
    }

    /**
     * Догрузка: следующая страница у каждого источника, у которого она есть.
     *
     * Провал не стирает уже показанные активности, но и молча дёргать сеть в
     * цикле нельзя — список не вырос, автотриггер по концу списка больше не
     * сработает, поэтому хвост переходит в «повторить» вместе с причиной.
     *
     * Источник, у которого страницы кончились, из курсора
     * [ActivityState.nextPages] исчезает и больше не спрашивается. А вот
     * источник, **не ответивший** на догрузку, остаётся в курсоре на той же
     * странице: иначе одна неудачная страница навсегда выкинула бы весь
     * раздел из догрузки, и хвоста его активностей человек не увидел бы уже
     * никогда.
     *
     * Поэтому любой отказ догрузки, а не только полный, показывается хвостом
     * списка: молча не догрузить часть списка — то же, что потерять её.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.items.dataOrNull() ?: return
        if (loadMoreJob?.isActive == true) return

        val pages = state.nextPages
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val feed = repository.feed(pages = pages)
            val retryPages = pages.filterKeys { it in feed.failures }
            updateState {
                copy(
                    items = ScreenState.Content(appended(loaded, feed.items)),
                    // Отказ догрузки показывается хвостом списка, а не
                    // отметкой раздела: раздел уже показан выше своими первыми
                    // страницами, и «не загрузился» про него было бы неправдой.
                    loadMoreFailure = feed.failures.values.firstOrNull(),
                    nextPages = feed.nextPages + retryPages,
                    isLoadingMore = false,
                )
            }
        }
    }

    /**
     * Догруженные страницы приклеиваются с дедупликацией: страницы пяти
     * источников приезжают в разное время, и активность с границы страниц
     * приедет дважды — в `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(current: List<Activity>, next: List<Activity>): List<Activity> =
        ActivityMerge.append(current, next)

    /**
     * Переход по строке. Цель разбирает [ActivityTarget]: у брони, записи и
     * билета экрана ещё нет, поэтому эффекта нет вовсе — такая строка и не
     * кликабельна.
     */
    private fun open(key: String) {
        val activity = currentState.items.dataOrNull()?.firstOrNull { it.key == key } ?: return
        when (val target = activity.target) {
            is ActivityTarget.FoodOrder ->
                emitEffect(ActivityEffect.OpenFoodOrder(target.orderId))

            ActivityTarget.None -> Unit
        }
    }
}
