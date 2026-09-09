package uz.mahalla.feature.activity.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivityMerge
import uz.mahalla.feature.activity.domain.ActivitySource
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

    /**
     * Экран уже был на переднем плане. Нужен, чтобы отличить **возврат** на
     * экран от его открытия — см. [onScreenResumed].
     */
    private var resumedOnce = false

    init {
        load()
    }

    override fun onEvent(event: ActivityEvent) {
        when (event) {
            ActivityEvent.ScreenResumed -> onScreenResumed()

            ActivityEvent.Refreshed -> load(showLoading = false, refreshing = true)

            // «Повторить» приходит из двух мест: из полного отказа (там
            // показывать скелетон правильно — списка нет) и из отметки
            // сбойного раздела при частичном отказе. Во втором случае список
            // уже на экране, и заменять его скелетоном значит забрать у
            // человека то, что он читает, из-за раздела, который его,
            // возможно, вообще не интересует.
            ActivityEvent.Retry -> load(showLoading = currentState.items !is ScreenState.Content)
            ActivityEvent.LoadMore -> loadMore()

            // Переключение вкладки сети не касается — кроме одного случая:
            // вкладка, на которую перешли, пуста, а страницы ещё есть. Тогда
            // «пусто» — не ответ, а недогруженный список (issue #143).
            //
            // Тап по уже выбранной вкладке — не переключение: `selectable`
            // зовёт `onClick` и на выбранном элементе, а человек, увидевший
            // «активных нет», тычет в «Faol» именно так. Каждый такой тап
            // запускал бы догрузку заново и стирал бы из хвоста «повторить».
            is ActivityEvent.FilterSelected -> if (event.filter != currentState.filter) {
                updateState { copy(filter = event.filter) }
                if (currentState.visible.isEmpty()) loadMore()
            }
            is ActivityEvent.ActivityClicked -> open(event.key)
            ActivityEvent.DiscoveryRequested -> emitEffect(ActivityEffect.OpenDiscovery)
        }
    }

    /**
     * Возврат на экран: пока приложение было в фоне, заказ могли собрать, а
     * бронь — подтвердить, и таб открывают как раз затем, чтобы это увидеть.
     *
     * **Первый resume пропускается.** `LifecycleEventEffect(ON_RESUME)`
     * срабатывает на первой же композиции, то есть сразу после того, как
     * список запросил `init` — и это не возврат на экран, а его открытие.
     * Проверки `isLoading` для этого мало: она отсекает дубль только пока
     * стартовая загрузка в полёте, а успела та дойти до конца — и экран
     * открывался бы двумя одинаковыми загрузками, то есть **десятью**
     * запросами к пяти источникам вместо пяти (issue #145).
     *
     * Флаг живёт в ViewModel, а не в композабле: композабл пересоздаётся при
     * каждом уходе с таба, а ViewModel держится за запись бэкстека — и второй
     * его resume перечитать список как раз обязан.
     */
    private fun onScreenResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        // Пока загрузка в полёте, перезапрашивать нечего: ответ приедет на уже
        // сменившееся состояние. Проверяется job, а не `isLoading` с
        // `isRefreshing`: загрузка **от самого resume** идёт молча и ни одного
        // из этих флагов не поднимает, так что два resume подряд (диалог
        // поверх экрана, быстрый уход в фон и обратно) снова дали бы десять
        // запросов вместо пяти.
        if (loadJob?.isActive == true) return
        load(showLoading = false)
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        // И предыдущую загрузку тоже: два ответа на один экран — это список от
        // одного запроса с курсором от другого. Возврат на экран во время
        // pull-to-refresh даёт ровно такую пару.
        loadJob?.cancel()
        updateState {
            copy(
                items = if (showLoading) ScreenState.Loading else items,
                // Отметки сбойных разделов держатся на списке, который они
                // объясняют: уходит список в скелетон — уходят и они. Иначе
                // «повторить» оставило бы пять строк «не загрузилось» висеть
                // поверх скелетона.
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
                        feed.items.isEmpty() -> ScreenState.Empty
                        else -> ScreenState.Content(feed.items)
                    },
                    // При полном отказе разделы не отмечаются: экран уже
                    // показывает одну общую ошибку с одной кнопкой
                    // «повторить», а пять строк «не загрузилось» рядом с ней —
                    // шесть сообщений об одном и том же 401.
                    sourceFailures = if (feed.isTotalFailure) emptyMap() else feed.failures,
                    nextPages = feed.nextPages,
                    isRefreshing = false,
                )
            }
            // Первая страница могла целиком уехать в другую вкладку: двадцать
            // выполненных заказов в «истории», а активный — на второй
            // странице. На «активных» при этом пусто, и остановиться на этом
            // значит соврать, что активностей нет (issue #143).
            //
            // Догрузка идёт этой же корутиной, а не через `loadMore()`: та
            // отказывается стартовать, пока загрузка в полёте, — а мы внутри
            // неё и есть.
            if (currentState.visible.isEmpty()) drain()
        }
    }

    /**
     * Догрузка: следующая страница у каждого источника, у которого она есть.
     *
     * Приходит по нажатию кнопки «показать ещё» в хвосте списка — сам по себе
     * этот запрос экран больше не делает (issue #151). Второй вызывающий один —
     * переход на **пустую** вкладку (`FilterSelected` выше): ту доливает
     * [drain] изнутри, не дожидаясь нажатия (issue #143).
     *
     * Провал не стирает уже показанные активности, но и молча дёргать сеть в
     * цикле нельзя, поэтому хвост переходит в «повторить» вместе с причиной.
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
     * Страниц за одно нажатие может уйти несколько: пока текущая вкладка пуста,
     * а список от страницы к странице растёт, догрузка идёт сама — см. [drain].
     * Как только на вкладке появилось что показать, одно нажатие — одна
     * страница.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        if (state.items.dataOrNull() == null) return
        // Загрузка первой страницы в полёте — её ответ вот-вот заменит и
        // список, и курсор. Догрузка со старого курсора приклеила бы к новому
        // списку страницу от предыдущего и разошлась бы с ним же.
        //
        // Здесь же отсекается pull-to-refresh (issue #145): `isRefreshing`
        // отдельной проверкой не нужен — обновление идёт тем же `loadJob`, и
        // активен он строго дольше, чем поднят флаг. Обратный порядок —
        // обновление стартовало **после** догрузки — держится на отмене в
        // `load()`: отменённая догрузка своего ответа уже не применяет.
        if (loadJob?.isActive == true || loadMoreJob?.isActive == true) return

        loadMoreJob = viewModelScope.launch { drain() }
    }

    /**
     * Догрузка страницами, пока текущая вкладка пуста.
     *
     * Пустая вкладка при непустом курсоре — не конец списка, а недогруженный
     * список: вкладку отбирает **клиент**, и приехавшая страница могла целиком
     * уехать в соседнюю. Останавливаться на этом нельзя — человек на «активных»
     * увидел бы «активных нет, всё в истории», хотя активное лежит на второй
     * странице (issue #143).
     *
     * На хвост списка это дело переложить нельзя: там теперь кнопка «показать
     * ещё» (issue #151), а нажимать её на пустой вкладке некому — человек видит
     * «активных нет» и уходит, так и не узнав, что активное лежало страницей
     * ниже.
     *
     * Условия остановки — четыре, и три из них про то, чтобы цикл не стал
     * бесконечным:
     * - вкладка перестала быть пустой: дальше страницы идут по кнопке, по одной
     *   на нажатие, а не разом;
     * - страница не удалась: причина уходит в хвост с кнопкой «повторить», а
     *   молча дёргать сеть по кругу нельзя;
     * - **страница не принесла ничего нового**: дедупликация съела её целиком,
     *   значит сервер отдаёт один и тот же хвост, и следующий запрос будет
     *   ровно таким же. Проверка именно по списку, а не по номеру страницы:
     *   номер считает клиент (`запрошенная + 1`), и на сервере, который всегда
     *   отвечает `hasMore`, он растёт вечно;
     * - [MAX_DRAIN_PAGES] страниц за раз: столько истории подряд — это уже не
     *   догрузка вкладки, и остаток пусть тянут кнопкой.
     */
    private suspend fun drain() {
        if (currentState.items.dataOrNull() == null || !currentState.hasMore) return

        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        var page = 0
        while (true) {
            val pages = currentState.nextPages
            val before = currentState.items.dataOrNull().orEmpty()
            loadPage(pages, before)
            page++

            val loaded = currentState
            val grew = loaded.items.dataOrNull().orEmpty().size > before.size
            val goOn = loaded.visible.isEmpty() &&
                loaded.loadMoreFailure == null &&
                loaded.hasMore &&
                grew &&
                page < MAX_DRAIN_PAGES
            if (!goOn) break
        }
        updateState { copy(isLoadingMore = false) }
    }

    /**
     * Одна страница у каждого источника из курсора [pages]. [loaded] — список,
     * к которому её приклеить: он снят до запроса, чтобы дедупликация видела
     * ровно то, что показано.
     */
    private suspend fun loadPage(pages: Map<ActivitySource, Int>, loaded: List<Activity>) {
        val feed = repository.feed(pages = pages)
        val retryPages = pages.filterKeys { it in feed.failures }
        updateState {
            copy(
                items = ScreenState.Content(appended(loaded, feed.items)),
                // Отказ догрузки показывается хвостом списка, а не отметкой
                // раздела: раздел уже показан выше своими первыми страницами,
                // и «не загрузился» про него было бы неправдой.
                loadMoreFailure = feed.failures.values.firstOrNull(),
                nextPages = feed.nextPages + retryPages,
            )
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

    private companion object {
        /**
         * Потолок страниц за одну догрузку пустой вкладки. Двадцать страниц по
         * двадцать активностей — четыреста штук истории подряд без единой
         * активной: дальше это уже не «вкладка ещё не догрузилась», а
         * выкачивание всей истории на каждый возврат на экран. Остаток — по
         * кнопке «показать ещё» в хвосте: она показана и на пустой вкладке.
         */
        const val MAX_DRAIN_PAGES = 20
    }
}
