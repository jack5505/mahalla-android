package uz.mahalla.feature.activity.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivityMerge
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.feature.booking.domain.AppointmentVertical
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
            ActivityEvent.ScreenResumed -> onScreenResumed()

            ActivityEvent.Refreshed -> load(showLoading = false, refreshing = true)

            // «Повторить» приходит из двух мест: из полного отказа (там
            // показывать скелетон правильно — списка нет) и из отметки
            // сбойного раздела при частичном отказе. Во втором случае список
            // уже на экране, и заменять его скелетоном значит забрать у
            // человека то, что он читает, из-за раздела, который его,
            // возможно, вообще не интересует.
            ActivityEvent.Retry -> if (currentState.items is ScreenState.Content) {
                retryFailedSources()
            } else {
                load(showLoading = true)
            }
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
     * Защита от дубля (первый resume, два resume подряд) — общая, см.
     * [MviViewModel.onScreenResumed] (issue #145, #209): здесь список пяти
     * источников, поэтому дубль — не одна лишняя загрузка, а **десять**
     * запросов вместо пяти.
     */
    private fun onScreenResumed() = onScreenResumed(
        isLoadInFlight = { loadJob?.isActive == true },
        load = { resumeLoad() },
    )

    /**
     * Настоящий возврат на экран (issue #213): перечитывает ровно те страницы,
     * что уже набраны кнопкой «показать ещё» — список не растёт и не
     * схлопывается молча, только освежается. `load()` сюда не годится: она
     * всегда перечитывает только [ActivityFeed.FIRST_PAGES], а три нажатия
     * «показать ещё» перед уходом с таба откатились бы к первой странице.
     */
    private fun resumeLoad() {
        // Пустой курсор — это `ScreenState.Error`: не ответил вообще никто, и
        // [replay] не знает, сколько страниц спрашивать (там и было бы ноль).
        // Тут нужен не повтор старого, а честная полная загрузка — ровно то,
        // что раньше давал возврат на экран, и терять эту возможность
        // восстановиться нельзя.
        if (currentState.loadedPages.isEmpty()) {
            load(showLoading = false)
            return
        }
        loadMoreJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch { replay() }
    }

    /**
     * Проход за проходом перечитывает [ActivityState.loadedPages] страниц
     * каждого источника — так же, как они когда-то были набраны. Источник без
     * ответа в прошлый раз (`loadedPages == 0`) всё равно спрашивается с
     * нулевой страницы: возврат на экран — честный шанс на успех, а не повод
     * держать отметку об отказе вечно.
     *
     * Источник останавливается сам, как только: он отказал (остаётся в
     * курсоре на той же странице, как при обычной догрузке); у него
     * кончились страницы раньше, чем ожидалось (что-то удалили за время в
     * фоне — не повод выдумывать страницы, которых больше нет); или он дошёл
     * до количества страниц, что были показаны — тогда курсор берётся из
     * последнего ответа, и дальше источник докладывается обычной кнопкой.
     */
    private suspend fun replay() {
        val target = currentState.loadedPages
        var remaining = target.mapValues { (_, loaded) -> loaded.coerceAtLeast(1) }
        var level = 0
        var items = emptyList<Activity>()
        val failures = mutableMapOf<ActivitySource, ApiFailure>()
        val nextPages = mutableMapOf<ActivitySource, Int>()
        val loadedPages = mutableMapOf<ActivitySource, Int>()

        while (remaining.isNotEmpty()) {
            val pages = remaining.keys.associateWith { level }
            val feed = repository.feed(pages = pages)
            items = appended(items, feed.items)

            pages.keys.forEach { source ->
                if (source in feed.failures) {
                    failures[source] = feed.failures.getValue(source)
                    nextPages[source] = level
                    loadedPages[source] = level
                    remaining = remaining - source
                } else {
                    loadedPages[source] = level + 1
                    val hasMore = source in feed.nextPages
                    val reachedTarget = level + 1 >= remaining.getValue(source)
                    when {
                        !hasMore -> remaining = remaining - source
                        reachedTarget -> {
                            nextPages[source] = feed.nextPages.getValue(source)
                            remaining = remaining - source
                        }
                    }
                }
            }
            level++
        }

        // Не ответил вообще никто — тот же самый смысл, что и в `load()`
        // (`ActivityFeed.isTotalFailure`), только собранный по нескольким
        // проходам с разных страниц: источник попадает в [failures] только
        // если у него не было ни одного успешного ответа за весь [replay], а
        // значит совпадение размеров означает, что все пять отказали. Без
        // этой проверки видимый список стёрся бы в пустой `Content` с пятью
        // отметками об одном и том же 401 вместо одного экрана ошибки.
        val totalFailure = items.isEmpty() && failures.size == target.size
        updateState {
            copy(
                items = when {
                    totalFailure -> ScreenState.Error(failures.values.first())
                    items.isEmpty() && nextPages.isEmpty() -> ScreenState.Empty
                    else -> ScreenState.Content(items)
                },
                sourceFailures = if (totalFailure) emptyMap() else failures,
                nextPages = if (totalFailure) emptyMap() else nextPages,
                loadedPages = if (totalFailure) emptyMap() else loadedPages,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
    }

    /**
     * «Повторить» у отметки частичного отказа (issue #213, находка ревью
     * PR #215). Спрашивает только отказавшие источники, и только с их
     * страницы — `load()` перечитала бы [ActivityFeed.FIRST_PAGES] у всех
     * пяти и откатила бы курсор источников, которые ни в чём не виноваты.
     *
     * Страница берётся из [ActivityState.nextPages]: у отказа из первой
     * загрузки (`load()`) там всегда пусто — она у каждого источника одна на
     * все пять, нулевая, — а у отказа во время [replay] (тоже частичного:
     * полный там уходит в `ScreenState.Error` и сюда не попадает) там уже
     * стоит страница, на которой источник остановился.
     */
    private fun retryFailedSources() {
        val failedSources = currentState.sourceFailures.keys
        if (failedSources.isEmpty()) return

        loadMoreJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val loaded = currentState.items.dataOrNull().orEmpty()
            val pages = failedSources.associateWith { source -> currentState.nextPages[source] ?: 0 }
            val feed = repository.feed(pages = pages)
            updateState {
                copy(
                    items = ScreenState.Content(appended(loaded, feed.items)),
                    sourceFailures = sourceFailures - failedSources + feed.failures,
                    nextPages = nextPages + feed.nextPages,
                    loadedPages = loadedPages + pages.mapValues { (source, page) ->
                        if (source in feed.failures) page else page + 1
                    },
                )
            }
        }
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
                        // Пусто, но курсор не пуст — это не «вы ещё ничего не
                        // заказывали», а недогруженная страница (issue #203):
                        // например, первая страница целиком ушла в записи с
                        // неразбираемой датой. `Content(emptyList())` ниже
                        // отправит [drain] за следующей страницей — в отличие
                        // от `Empty`, у которого нет ни хвоста, ни догрузки.
                        feed.items.isEmpty() && !feed.hasMore -> ScreenState.Empty
                        else -> ScreenState.Content(feed.items)
                    },
                    // При полном отказе разделы не отмечаются: экран уже
                    // показывает одну общую ошибку с одной кнопкой
                    // «повторить», а пять строк «не загрузилось» рядом с ней —
                    // шесть сообщений об одном и том же 401.
                    sourceFailures = if (feed.isTotalFailure) emptyMap() else feed.failures,
                    nextPages = feed.nextPages,
                    // Возврату на экран (issue #213) нужно знать, сколько
                    // страниц каждого источника показано, чтобы потом
                    // перечитать столько же, а не откатить список к первой.
                    // Отказавший источник — ноль: он ничего не показал.
                    loadedPages = if (feed.isTotalFailure) {
                        emptyMap()
                    } else {
                        feed.requested.associateWith { source -> if (source in feed.failures) 0 else 1 }
                    },
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
        updateState {
            copy(
                isLoadingMore = false,
                // Курсор кончился, а активностей за все страницы так и не
                // нашлось — это не «пусто в этой вкладке» (issue #143, там
                // список в целом не пуст), а настоящее «вы ещё ничего не
                // заказывали» (issue #203).
                items = if (!hasMore && items.dataOrNull()?.isEmpty() == true) {
                    ScreenState.Empty
                } else {
                    items
                },
            )
        }
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
                // Отказавшая страница курсор не сдвинула (см. [retryPages]) —
                // значит и счётчик для возврата на экран (issue #213) ей
                // считать не за что.
                loadedPages = loadedPages + pages.keys.filterNot { it in feed.failures }
                    .associateWith { source -> (loadedPages[source] ?: 0) + 1 },
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
     * Переход по строке. Цель разбирает [ActivityTarget]: у брони экрана ещё
     * нет, поэтому эффекта нет вовсе — такая строка и не кликабельна.
     */
    private fun open(key: String) {
        val activity = currentState.items.dataOrNull()?.firstOrNull { it.key == key } ?: return
        when (val target = activity.target) {
            is ActivityTarget.FoodOrder ->
                emitEffect(ActivityEffect.OpenFoodOrder(target.orderId))

            is ActivityTarget.CinemaTicket ->
                emitEffect(ActivityEffect.OpenTicket(target.ticketId))

            is ActivityTarget.MasterAppointment -> emitEffect(
                ActivityEffect.OpenAppointment(target.appointmentId, AppointmentVertical.Barber.name),
            )

            is ActivityTarget.DoctorAppointment -> emitEffect(
                ActivityEffect.OpenAppointment(target.appointmentId, AppointmentVertical.Doctor.name),
            )

            ActivityTarget.None -> Unit
        }
    }

    private companion object {
        /**
         * Потолок проходов за одну догрузку пустой вкладки. Проход — это одна
         * страница **каждого** источника с непустым курсором, то есть до пяти
         * запросов разом; двадцать проходов — до ста запросов и до двух тысяч
         * активностей истории без единой активной. Дальше это уже не «вкладка
         * ещё не догрузилась», а выкачивание всей истории на каждый возврат на
         * экран. Остаток — по кнопке «показать ещё» в хвосте: она показана и на
         * пустой вкладке.
         */
        const val MAX_DRAIN_PAGES = 20
    }
}
