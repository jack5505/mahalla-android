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

    private var loadJob: Job? = null
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
                if (loadJob?.isActive != true &&
                    !currentState.items.isLoading &&
                    !currentState.isRefreshing
                ) {
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

    /**
     * Перезагрузка всех источников с нулевой страницы.
     *
     * Предыдущая перезагрузка отменяется, а не игнорируется: «повторить» при
     * частичном отказе висит на **каждой** отметке сбойного раздела, и два
     * тапа подряд иначе дали бы две параллельные пятёрки запросов, чей порядок
     * ответов определял бы, что окажется на экране.
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        updateState {
            copy(
                items = if (showLoading) ScreenState.Loading else items,
                // Отметки сбойных разделов относятся к прошлому ответу. Над
                // скелетоном они висели бы враньём: раздел уже перезапрашивают.
                sourceFailures = if (showLoading) emptyMap() else sourceFailures,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        loadJob = viewModelScope.launch {
            val feed = repository.feed(pages = ActivityFeed.FIRST_PAGES)
            updateState {
                copy(
                    items = when {
                        // Не ответил вообще никто — только это ошибка экрана.
                        // Причину берём у любого источника: при полном отказе
                        // она у всех одна и та же (401, таймаут, нет сети).
                        feed.isTotalFailure -> ScreenState.Error(feed.failures.values.first())
                        // Пусто — только когда догружать больше нечего.
                        // Страница может целиком уехать в `mapNotNull` (двадцать
                        // записей без `id`), и `Empty` при живом курсоре
                        // означал бы тупик: хвост списка рисуется лишь в ветке
                        // `Content`, а вместе с ним пропадает и его
                        // `LaunchedEffect` — ни автодогрузки, ни кнопки. Пустой
                        // `Content` при `hasMore` показывает хвост и цепочка
                        // идёт дальше; «пустой вкладкой» его не объявят —
                        // `isTabEmpty` требует, чтобы хвоста не было.
                        feed.items.isEmpty() && !feed.hasMore -> ScreenState.Empty
                        else -> ScreenState.Content(feed.items)
                    },
                    // При полном отказе отметок разделов нет: экран и так
                    // показывает одну ошибку, а пять строк «не загрузилось»
                    // рядом с ней — это шесть сообщений и шесть кнопок
                    // «повторить» об одном и том же.
                    sourceFailures = if (feed.isTotalFailure) emptyMap() else feed.failures,
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
     *
     * Список и курсор берутся **после** идущей перезагрузки, а не до неё.
     * Запрос догрузки может уйти в тот момент, когда `load()` уже летит
     * (возврат на экран заодно гасит `loadMoreFailure`, хвост переключается с
     * «повторить» на крутилку, и её `LaunchedEffect` тут же просит следующую
     * страницу). Отредуцировать такую догрузку от снимка, снятого до
     * перезагрузки, значило бы молча заменить свежий ответ на устаревший
     * список плюс одна страница.
     */
    private fun loadMore() {
        if (!currentState.hasMore || currentState.isLoadingMore) return

        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            // Перезагрузок может быть несколько подряд: каждая следующая
            // отменяет предыдущую, и `join` по одной ссылке вернулся бы к
            // всё ещё летящему запросу.
            while (loadJob?.isActive == true) loadJob?.join()

            val state = currentState
            val loaded = state.items.dataOrNull()
            val pages = state.nextPages
            if (loaded == null || pages.isEmpty()) {
                updateState { copy(isLoadingMore = false) }
                return@launch
            }

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
