package uz.mahalla.feature.activity.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivityFilter
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.testutil.FakeActivityRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Instant

/**
 * «Мои активности» (issue #73, задача T7).
 *
 * Главное, что проверяется: слияние источников и **частичный отказ** — один
 * сбойный источник не имеет права уронить весь экран.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `activities of all sources land in one list`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity("o-1", ActivitySource.Orders),
                activity("b-1", ActivitySource.GamingBookings),
                activity("t-1", ActivitySource.CinemaTickets),
            ),
        )

        val state = ActivityViewModel(repository).state.value

        assertEquals(3, (state.items as ScreenState.Content).data.size)
        // Первая загрузка спрашивает все пять источников с нулевой страницы.
        assertEquals(ActivityFeed.FIRST_PAGES, repository.requests.single())
        assertTrue(state.sourceFailures.isEmpty())
    }

    @Test
    fun `one failed source is marked but the list stays`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            failures = mapOf(
                ActivitySource.GamingBookings to ApiFailure(
                    error = ApiError.Business("GAMING_UNAVAILABLE"),
                    server = ServerError(httpCode = 200, message = "Bandlovlar ishlamayapti"),
                ),
            ),
        )

        val state = ActivityViewModel(repository).state.value

        // Ровно то, чего требует T7: данные показаны, сбойный раздел отмечен.
        assertTrue(state.items is ScreenState.Content)
        assertEquals(setOf(ActivitySource.GamingBookings), state.sourceFailures.keys)
        assertEquals(
            "Bandlovlar ishlamayapti",
            state.sourceFailures.getValue(ActivitySource.GamingBookings).serverMessage,
        )
    }

    @Test
    fun `retrying a failed section does not blank the list`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            failures = mapOf(ActivitySource.GamingBookings to ApiFailure(ApiError.Timeout)),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.Retry)

        // Список уже на экране: заменять его скелетоном из-за одного раздела
        // значит забрать у человека то, что он читает.
        assertTrue(viewModel.state.value.items is ScreenState.Content)
        assertEquals(2, repository.requests.size)
    }

    @Test
    fun `nobody answered is the only real screen failure`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            failures = ActivityFeed.FIRST_PAGES.keys.associateWith { ApiFailure(ApiError.Unauthorized) },
        )

        val state = ActivityViewModel(repository).state.value

        // Истёкшая сессия — не «вы ещё ничего не заказывали».
        assertEquals(ApiError.Unauthorized, (state.items as ScreenState.Error).error)
        // И не пять отметок разделов рядом с общей ошибкой: экран показал бы
        // шесть сообщений об одном и том же 401, с шестью «повторить».
        assertTrue(state.sourceFailures.isEmpty())
    }

    @Test
    fun `retrying a failed section only asks that source, keeping other cursors`() = runTest {
        // Находка ревью PR #215: раньше «повторить» перечитывало
        // FIRST_PAGES у всех пяти источников и откатывало курсор тех, что ни
        // в чём не виноваты. Три страницы «Заказов» набраны кнопкой «показать
        // ещё» — «повторить» у отметки «Кино» не должно их тронуть.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            items = listOf(activity("o-2", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.Orders to 2),
        )
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.LoadMore)
        assertEquals(mapOf(ActivitySource.Orders to 2), viewModel.state.value.nextPages)

        repository.feeds[setOf(ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("t-1", ActivitySource.CinemaTickets)),
        )
        viewModel.onEvent(ActivityEvent.Retry)
        val state = viewModel.state.value

        // «Кино» вылечилось и попало в список, а курсор «Заказов» — тот же,
        // что был до «повторить».
        assertTrue(state.sourceFailures.isEmpty())
        assertEquals(mapOf(ActivitySource.Orders to 2), state.nextPages)
        assertEquals(
            setOf("Orders:o-1", "Orders:o-2", "CinemaTickets:t-1"),
            (state.items as ScreenState.Content).data.map(Activity::key).toSet(),
        )
        // Ровно один новый запрос — только за «Кино».
        assertEquals(mapOf(ActivitySource.CinemaTickets to 0), repository.requests.last())
    }

    @Test
    fun `retrying a failed section resets a load-more spinner it cancelled`() = runTest {
        // Блокер ревью PR #332: `retryFailedSources()` отменяет `loadMoreJob`,
        // а тот как раз держал `isLoadingMore = true`. До этой правки флаг
        // чинил только сам отменённый `drain()` — его отмена обрывает
        // выполнение до финального `updateState`, и спиннер оставался висеть
        // навсегда: `loadMore()` выходит по `state.isLoadingMore` и больше не
        // стартует.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        val viewModel = ActivityViewModel(repository)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.onEvent(ActivityEvent.LoadMore)
        assertTrue(viewModel.state.value.isLoadingMore)

        repository.gate = null
        repository.feeds[setOf(ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("t-1", ActivitySource.CinemaTickets)),
        )
        viewModel.onEvent(ActivityEvent.Retry)

        assertFalse(viewModel.state.value.isLoadingMore)
        assertTrue(viewModel.state.value.sourceFailures.isEmpty())

        gate.complete(Unit)
    }

    @Test
    fun `retrying a failed section resets a refresh indicator it cancelled`() = runTest {
        // То же самое про pull-to-refresh: `Refreshed -> load(refreshing = true)`
        // оставляет список и отметки разделов на экране с `isRefreshing = true`
        // до ответа. Тап по «повторить» отменял этот `loadJob`, но не сбрасывал
        // флаг — индикатор `MahallaPullToRefresh` крутился бы бесконечно.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
        )
        val viewModel = ActivityViewModel(repository)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.onEvent(ActivityEvent.Refreshed)
        assertTrue(viewModel.state.value.isRefreshing)

        repository.gate = null
        repository.feeds[setOf(ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("t-1", ActivitySource.CinemaTickets)),
        )
        viewModel.onEvent(ActivityEvent.Retry)

        assertFalse(viewModel.state.value.isRefreshing)

        gate.complete(Unit)
    }

    @Test
    fun `retrying a failed section clears the cursor of a source that just finished`() = runTest {
        // Не-блокер ревью PR #332: `nextPages = nextPages + feed.nextPages` не
        // убирает запись у вылечившегося источника. Источник, отказавший в
        // `replay()` на второй странице, держит `nextPages[src] = 1`; если
        // «повторить» приносит его последнюю страницу (`feed.nextPages` про
        // него молчит), старая запись остаётся, `hasMore` держится вечно, и
        // «показать ещё» бьёт в пустоту.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.CinemaTickets to 1),
        )
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed) // открытие, не в счёт.

        // Кнопка «показать ещё» доводит «Кино» до второй страницы.
        repository.feeds[setOf(ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("t-1", ActivitySource.CinemaTickets)),
            nextPages = mapOf(ActivitySource.CinemaTickets to 2),
        )
        viewModel.onEvent(ActivityEvent.LoadMore)
        assertEquals(mapOf(ActivitySource.CinemaTickets to 2), viewModel.state.value.nextPages)

        // Возврат на экран реплеит обе страницы «Кино»: первая успевает,
        // вторая отказывает — курсор остаётся на {CinemaTickets: 1}.
        var replayAsked = false
        repository.pageFeeds = { pages ->
            if (pages.keys == setOf(ActivitySource.CinemaTickets) && !replayAsked) {
                replayAsked = true
                ActivityFeed(failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)))
            } else {
                null
            }
        }
        viewModel.onEvent(ActivityEvent.ScreenResumed)
        assertEquals(mapOf(ActivitySource.CinemaTickets to 1), viewModel.state.value.nextPages)

        // «Повторить» приносит последнюю страницу «Кино» — курсор источника
        // должен исчезнуть, а не остаться висеть на старой странице.
        repository.pageFeeds = null
        repository.feeds[setOf(ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("t-2", ActivitySource.CinemaTickets)),
        )
        viewModel.onEvent(ActivityEvent.Retry)

        assertTrue(viewModel.state.value.sourceFailures.isEmpty())
        assertTrue(viewModel.state.value.nextPages.isEmpty())
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `a resume from a total failure still does a full reload`() = runTest {
        // Пустой курсор при полном отказе (issue #213) — не про что
        // перечитывать, а перечитывать никого не значит потерять единственный
        // шанс выйти из ScreenState.Error возвратом на таб.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            failures = ActivityFeed.FIRST_PAGES.keys.associateWith { ApiFailure(ApiError.Unauthorized) },
        )
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed)
        assertTrue(viewModel.state.value.items is ScreenState.Error)

        repository.defaultFeed = ActivityFeed(items = listOf(activity("o-1", ActivitySource.Orders)))
        viewModel.onEvent(ActivityEvent.ScreenResumed)

        assertEquals(ActivityFeed.FIRST_PAGES, repository.requests.last())
        assertTrue(viewModel.state.value.items is ScreenState.Content)
    }

    @Test
    fun `an empty answer from everyone is an empty state, not an error`() = runTest {
        val repository = FakeActivityRepository()

        val state = ActivityViewModel(repository).state.value

        assertTrue(state.items is ScreenState.Empty)
        assertFalse(state.hasMore)
    }

    @Test
    fun `an empty source with no data is still just empty`() = runTest {
        // Один источник промолчал ошибкой, остальные ответили пустыми: это не
        // полный отказ, поэтому пустое состояние плюс отметка раздела.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
        )

        val state = ActivityViewModel(repository).state.value

        assertTrue(state.items is ScreenState.Empty)
        assertEquals(setOf(ActivitySource.CinemaTickets), state.sourceFailures.keys)
    }

    // --- Фильтр ---

    @Test
    fun `the filter splits the loaded list without touching the network`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity("active", status = ActivityStatus.InProgress, at = "2026-09-04T10:00:00Z"),
                activity("done", status = ActivityStatus.Completed, at = "2026-09-01T10:00:00Z"),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        assertEquals(listOf("active"), viewModel.state.value.visible.map(Activity::id))

        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.History))

        assertEquals(listOf("done"), viewModel.state.value.visible.map(Activity::id))
        // Обе вкладки собираются из одной загрузки: тап по «истории» не должен
        // стоить пяти запросов.
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun `an empty tab is not an empty screen`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("done", status = ActivityStatus.Completed)),
        )
        val viewModel = ActivityViewModel(repository)

        // Всё выполнено: вкладка «активные» пуста, но человек-то не новичок —
        // экран не имеет права показать «вы ещё ничего не заказывали».
        assertTrue(viewModel.state.value.items is ScreenState.Content)
        assertTrue(viewModel.state.value.visible.isEmpty())

        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.History))

        assertEquals(listOf("done"), viewModel.state.value.visible.map(Activity::id))
    }

    // --- Пустая вкладка при непустом курсоре (issue #143) ---

    @Test
    fun `an empty tab pulls pages by itself until the cursor runs out`() = runTest {
        // Вся первая страница уехала в «историю», а активный заказ — на
        // третьей. Пустая вкладка не имеет права остановить догрузку: вкладку
        // отбирает клиент, и просить следующую страницу больше некому —
        // человек на «Faol» видел бы «активных нет, всё в истории», и это
        // неправда.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            when (pages.getValue(ActivitySource.Orders)) {
                0 -> ActivityFeed(
                    items = listOf(
                        activity("done-1", status = ActivityStatus.Completed, at = "2026-09-01T10:00:00Z"),
                    ),
                    nextPages = mapOf(ActivitySource.Orders to 1),
                )

                1 -> ActivityFeed(
                    items = listOf(
                        activity("done-2", status = ActivityStatus.Completed, at = "2026-09-02T10:00:00Z"),
                    ),
                    nextPages = mapOf(ActivitySource.Orders to 2),
                )

                else -> ActivityFeed(
                    items = listOf(activity("active", status = ActivityStatus.InProgress)),
                )
            }
        }

        val viewModel = ActivityViewModel(repository)
        val state = viewModel.state.value

        // Догрузка шла сама, пока курсор не опустел, — и активное приехало.
        assertEquals(listOf("active"), state.visible.map(Activity::id))
        assertEquals(
            listOf(0, 1, 2),
            repository.requests.map { it.getValue(ActivitySource.Orders) },
        )
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)

        // И ни одна из пройденных страниц не потерялась по дороге.
        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.History))
        assertEquals(listOf("done-2", "done-1"), viewModel.state.value.visible.map(Activity::id))
    }

    @Test
    fun `the drain stops as soon as the tab has something to show`() = runTest {
        // Курсор ещё не пуст, но качать дальше незачем: на вкладке уже есть
        // что читать, остальное человек дотянет кнопкой «показать ещё».
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            when (pages.getValue(ActivitySource.Orders)) {
                0 -> ActivityFeed(
                    items = listOf(activity("done", status = ActivityStatus.Completed)),
                    nextPages = mapOf(ActivitySource.Orders to 1),
                )

                else -> ActivityFeed(
                    items = listOf(activity("active", status = ActivityStatus.InProgress)),
                    nextPages = mapOf(ActivitySource.Orders to 2),
                )
            }
        }

        val viewModel = ActivityViewModel(repository)
        val state = viewModel.state.value

        assertEquals(2, repository.requests.size)
        assertEquals(listOf("active"), state.visible.map(Activity::id))
        assertTrue(state.hasMore)
    }

    @Test
    fun `a page that brings nothing new stops the drain`() = runTest {
        // Сервер отдаёт один и тот же хвост. Номер следующей страницы считает
        // клиент («запрошенная + 1»), поэтому по курсору такой цикл не
        // отличить от честной догрузки — смотреть надо на список: страница,
        // которую целиком съела дедупликация, следующей не сдвинет ничего.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            ActivityFeed(
                items = listOf(activity("done", status = ActivityStatus.Completed)),
                nextPages = mapOf(ActivitySource.Orders to pages.getValue(ActivitySource.Orders) + 1),
            )
        }

        val viewModel = ActivityViewModel(repository)

        assertEquals(2, repository.requests.size)
        assertTrue(viewModel.state.value.visible.isEmpty())
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `the drain gives up after a sane number of pages`() = runTest {
        // История, у которой нет конца: качать её всю на каждый возврат на
        // экран нельзя. Дальше потолка тянет хвост списка — на пустой вкладке
        // он и так на виду.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            val page = pages.getValue(ActivitySource.Orders)
            ActivityFeed(
                items = listOf(activity("done-$page", status = ActivityStatus.Completed)),
                nextPages = mapOf(ActivitySource.Orders to page + 1),
            )
        }

        val viewModel = ActivityViewModel(repository)
        val state = viewModel.state.value

        // Первая страница плюс потолок догрузки (`MAX_DRAIN_PAGES`).
        assertEquals(21, repository.requests.size)
        assertTrue(state.hasMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `a tab tap while the list is reloading does not use the old cursor`() = runTest {
        // Ответ обновления вот-вот заменит и список, и курсор. Догрузка,
        // начатая тапом по вкладке в этот момент, приклеила бы к новому списку
        // страницу от предыдущего — и разошлась бы с ним курсором.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("active", status = ActivityStatus.InProgress)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        val viewModel = ActivityViewModel(repository)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.onEvent(ActivityEvent.Refreshed)

        // «История» пуста — обычно это повод долить страницу, но не сейчас.
        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.History))

        assertEquals(2, repository.requests.size)

        repository.gate = null
        gate.complete(Unit)

        assertTrue(viewModel.state.value.items is ScreenState.Content)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a tap on the tab that is already open does not touch the network`() = runTest {
        // `selectable` зовёт `onClick` и на выбранном элементе: человек,
        // увидевший «активных нет», тычет в «Faol» именно так.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("done", status = ActivityStatus.Completed)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            failures = mapOf(ActivitySource.Orders to ApiFailure(ApiError.Timeout)),
        )
        val viewModel = ActivityViewModel(repository)
        assertEquals(2, repository.requests.size)

        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.Active))

        // Ни нового запроса, ни стёртой причины: иначе кнопка «повторить»
        // исчезала бы из хвоста от каждого тычка по своей же вкладке.
        assertEquals(2, repository.requests.size)
        assertEquals(ApiError.Timeout, viewModel.state.value.loadMoreFailure?.error)
    }

    @Test
    fun `a failed page on an empty tab stops the drain with a reason`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("done", status = ActivityStatus.Completed)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            failures = mapOf(ActivitySource.Orders to ApiFailure(ApiError.Timeout)),
        )

        val viewModel = ActivityViewModel(repository)
        val state = viewModel.state.value

        // Дёргать сеть по кругу молча нельзя: причина уходит в хвост списка
        // вместе с кнопкой «повторить», а источник остаётся в курсоре.
        assertEquals(2, repository.requests.size)
        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
        assertEquals(mapOf(ActivitySource.Orders to 1), state.nextPages)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `switching to an empty tab loads the pages it needs`() = runTest {
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            when (pages.getValue(ActivitySource.Orders)) {
                0 -> ActivityFeed(
                    items = listOf(activity("active", status = ActivityStatus.InProgress)),
                    nextPages = mapOf(ActivitySource.Orders to 1),
                )

                else -> ActivityFeed(
                    items = listOf(activity("done", status = ActivityStatus.Completed)),
                )
            }
        }
        val viewModel = ActivityViewModel(repository)

        // На «активных» есть что показать — доливать нечего.
        assertEquals(1, repository.requests.size)

        viewModel.onEvent(ActivityEvent.FilterSelected(ActivityFilter.History))

        // А «история» пуста при непустом курсоре: тап по вкладке её доливает.
        assertEquals(listOf("done"), viewModel.state.value.visible.map(Activity::id))
    }

    // --- Пусто у всех при непустом курсоре (issue #203) ---

    @Test
    fun `an empty first page does not dead-end the screen while the cursor still has pages`() = runTest {
        // Первая страница у всех источников пуста (например, все записи
        // разбитого формата даты не прошли маппер), а курсор не пуст. Раньше
        // это давало ScreenState.Empty без хвоста и без догрузки — выйти из
        // него можно было только pull-to-refresh, вернувшим то же самое.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            when (pages.getValue(ActivitySource.Orders)) {
                0 -> ActivityFeed(nextPages = mapOf(ActivitySource.Orders to 1))
                else -> ActivityFeed(items = listOf(activity("late", status = ActivityStatus.InProgress)))
            }
        }

        val viewModel = ActivityViewModel(repository)
        val state = viewModel.state.value

        // Догрузка прошла сама, и приехавшая активность видна.
        assertTrue(state.items is ScreenState.Content)
        assertEquals(listOf("late"), state.visible.map(Activity::id))
        assertFalse(state.hasMore)
    }

    @Test
    fun `an empty cursor that never produces anything ends in the real empty state`() = runTest {
        // Курсор кончился, а активностей так и не нашлось ни на одной
        // странице — это настоящее «вы ещё ничего не заказывали», а не
        // недогруженный список.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            when (pages.getValue(ActivitySource.Orders)) {
                0 -> ActivityFeed(nextPages = mapOf(ActivitySource.Orders to 1))
                else -> ActivityFeed()
            }
        }

        val state = ActivityViewModel(repository).state.value

        assertTrue(state.items is ScreenState.Empty)
        assertFalse(state.hasMore)
    }

    // --- Догрузка ---

    @Test
    fun `load more asks only the sources that have a next page`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            items = listOf(activity("o-2", ActivitySource.Orders)),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.LoadMore)
        val state = viewModel.state.value

        assertEquals(mapOf(ActivitySource.Orders to 1), repository.requests[1])
        assertEquals(
            listOf("o-1", "o-2"),
            (state.items as ScreenState.Content).data.map(Activity::id),
        )
        assertFalse(state.hasMore)
        assertNull(state.loadMoreFailure)
    }

    @Test
    fun `each tap on the button is one page, and no tap is no page`() = runTest {
        // Курсор не должен опустошаться сам (issue #151): бесконечная история
        // при непустой вкладке — это ровно одна страница на нажатие, а без
        // нажатий — ни одной сверх первой загрузки.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            val page = pages.getValue(ActivitySource.Orders)
            ActivityFeed(
                items = listOf(activity("active-$page", status = ActivityStatus.InProgress)),
                nextPages = mapOf(ActivitySource.Orders to page + 1),
            )
        }
        val viewModel = ActivityViewModel(repository)

        assertEquals(listOf(0), repository.requests.map { it.getValue(ActivitySource.Orders) })

        viewModel.onEvent(ActivityEvent.LoadMore)
        viewModel.onEvent(ActivityEvent.LoadMore)

        assertEquals(listOf(0, 1, 2), repository.requests.map { it.getValue(ActivitySource.Orders) })
        assertTrue(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a repeated activity on the page border is not duplicated`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders), activity("o-2", ActivitySource.Orders)),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.LoadMore)

        // Дубликат ключа роняет LazyColumn.
        assertEquals(
            listOf("o-1", "o-2"),
            (viewModel.state.value.items as ScreenState.Content).data.map(Activity::id),
        )
    }

    @Test
    fun `a failed load more keeps the source in the cursor`() = runTest {
        // Иначе одна неудачная страница навсегда выкинула бы весь раздел из
        // догрузки, и его хвоста человек не увидел бы уже никогда.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            failures = mapOf(ActivitySource.Orders to ApiFailure(ApiError.Timeout)),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.LoadMore)
        val state = viewModel.state.value

        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
        assertEquals(mapOf(ActivitySource.Orders to 1), state.nextPages)
        // Уже показанные активности провал догрузки не стирает.
        assertEquals(listOf("o-1"), (state.items as ScreenState.Content).data.map(Activity::id))
    }

    @Test
    fun `a partial load more failure still shows up in the tail`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("o-1", ActivitySource.Orders)),
            nextPages = mapOf(ActivitySource.Orders to 1, ActivitySource.CinemaTickets to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders, ActivitySource.CinemaTickets)] = ActivityFeed(
            items = listOf(activity("o-2", ActivitySource.Orders)),
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
            nextPages = mapOf(ActivitySource.Orders to 2),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.LoadMore)
        val state = viewModel.state.value

        // Молча не догрузить часть списка — то же, что потерять её.
        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
        // Успешный источник ушёл вперёд, сбойный остался на своей странице.
        assertEquals(
            mapOf(ActivitySource.Orders to 2, ActivitySource.CinemaTickets to 1),
            state.nextPages,
        )
    }

    @Test
    fun `load more does nothing when there is nothing more`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(items = listOf(activity("o-1", ActivitySource.Orders)))
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.LoadMore)

        assertEquals(1, repository.requests.size)
    }

    // --- Обновление ---

    @Test
    fun `coming back to the screen re-reads the list`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(items = listOf(activity("o-1", ActivitySource.Orders)))
        val viewModel = ActivityViewModel(repository)

        // Первый resume — это открытие экрана, его список уже запросил `init`.
        viewModel.onEvent(ActivityEvent.ScreenResumed)
        assertEquals(1, repository.requests.size)

        // А второй — настоящий возврат: пока приложение было в фоне, заказ
        // могли собрать.
        viewModel.onEvent(ActivityEvent.ScreenResumed)

        assertEquals(2, repository.requests.size)
        assertTrue(viewModel.state.value.items is ScreenState.Content)
    }

    @Test
    fun `a resume that loses every source collapses to the error screen, not a blank list`() = runTest {
        // Все пять источников успели отдать данные, а к возврату на таб
        // сессия истекла у всех разом. Это тот же смысл, что и
        // `ActivityFeed.isTotalFailure` в `load()`: нельзя молча стереть
        // видимый список до пустого `Content` с пятью одинаковыми отметками
        // «Unauthorized» — должен остаться один экран ошибки.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(items = listOf(activity("o-1", ActivitySource.Orders)))
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed) // первый resume — открытие, не в счёт.

        repository.defaultFeed = ActivityFeed(
            failures = ActivityFeed.FIRST_PAGES.keys.associateWith { ApiFailure(ApiError.Unauthorized) },
        )
        viewModel.onEvent(ActivityEvent.ScreenResumed) // настоящий возврат.
        val state = viewModel.state.value

        assertEquals(ApiError.Unauthorized, (state.items as ScreenState.Error).error)
        assertTrue(state.sourceFailures.isEmpty())
        assertFalse(state.hasMore)
    }

    @Test
    fun `coming back to the screen replays the pages already shown, not just the first`() = runTest {
        // Issue #213: три нажатия «показать ещё» набрали список из трёх
        // страниц. Уход на другой таб и возврат не имеет права откатить его к
        // одной первой странице.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            val page = pages.getValue(ActivitySource.Orders)
            ActivityFeed(
                items = listOf(activity("o-$page", ActivitySource.Orders)),
                nextPages = mapOf(ActivitySource.Orders to page + 1),
            )
        }
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed) // первый resume — открытие, не в счёт.
        viewModel.onEvent(ActivityEvent.LoadMore)
        viewModel.onEvent(ActivityEvent.LoadMore)
        assertEquals(listOf("o-0", "o-1", "o-2"), viewModel.state.value.visible.map(Activity::id))

        viewModel.onEvent(ActivityEvent.ScreenResumed) // настоящий возврат.

        assertEquals(listOf("o-0", "o-1", "o-2"), viewModel.state.value.visible.map(Activity::id))
        assertTrue(viewModel.state.value.hasMore)
    }

    @Test
    fun `resume replay is capped the same way drain is`() = runTest {
        // Не-блокер ревью PR #332: без потолка `replay()` реплеит ровно
        // столько страниц, сколько человек набрал кнопкой «показать ещё» за
        // много сессий — без ограничения простой возврат на таб мог стоить
        // десятков запросов вместо пяти. `MAX_DRAIN_PAGES` уже заведён против
        // ровно этого сценария у `drain()` — здесь та же граница.
        val repository = FakeActivityRepository()
        repository.pageFeeds = { pages ->
            val page = pages[ActivitySource.Orders]
            if (page != null) {
                ActivityFeed(
                    items = listOf(activity("o-$page", ActivitySource.Orders)),
                    nextPages = mapOf(ActivitySource.Orders to page + 1),
                )
            } else {
                ActivityFeed()
            }
        }
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed) // открытие, не в счёт.

        repeat(25) { viewModel.onEvent(ActivityEvent.LoadMore) }
        assertEquals(26, viewModel.state.value.loadedPages.getValue(ActivitySource.Orders))

        val before = repository.requests.size
        viewModel.onEvent(ActivityEvent.ScreenResumed) // настоящий возврат.

        // Потолок, а не 26 страниц, которые человек успел набрать кнопкой.
        assertEquals(20, repository.requests.size - before)
        assertTrue(viewModel.state.value.hasMore)
    }

    @Test
    fun `a resume that empties the tab pulls a new page, like load does`() = runTest {
        // Не-блокер ревью PR #332: `load()` доливает пустую вкладку при
        // непустом курсоре (issue #143), а `replay()` — нет. За время в фоне
        // единственный активный заказ мог завершиться, и «активных нет» без
        // попытки долить страницу было бы неправдой и на возврате на экран.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("active-0", status = ActivityStatus.InProgress)),
        )
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed) // открытие, не в счёт.

        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("done-0", status = ActivityStatus.Completed)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            items = listOf(activity("active-1", status = ActivityStatus.InProgress)),
        )
        viewModel.onEvent(ActivityEvent.ScreenResumed) // настоящий возврат.

        assertEquals(listOf("active-1"), viewModel.state.value.visible.map(Activity::id))
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `the first resume does not double the initial load`() = runTest {
        // `LifecycleEventEffect(ON_RESUME)` срабатывает на первой же
        // композиции. Стартовая загрузка к этому моменту могла успеть
        // завершиться — и тогда одной проверки `isLoading` не хватает: экран
        // открывался бы двумя одинаковыми загрузками, то есть десятью
        // запросами к пяти источникам вместо пяти (issue #145).
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("active", status = ActivityStatus.InProgress)),
        )
        val viewModel = ActivityViewModel(repository)
        assertEquals(1, repository.requests.size)

        viewModel.onEvent(ActivityEvent.ScreenResumed)

        assertEquals(listOf(ActivityFeed.FIRST_PAGES), repository.requests)
    }

    @Test
    fun `a second resume while the silent reload is in flight is ignored`() = runTest {
        // Загрузка от возврата на экран идёт молча: ни `isLoading`, ни
        // `isRefreshing` она не поднимает, поэтому по флагам состояния второй
        // resume подряд (диалог поверх экрана, быстрый уход в фон и обратно)
        // не отсечь — он снова стоил бы пяти запросов.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("active", status = ActivityStatus.InProgress)),
        )
        val viewModel = ActivityViewModel(repository)
        viewModel.onEvent(ActivityEvent.ScreenResumed)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.onEvent(ActivityEvent.ScreenResumed)
        assertEquals(2, repository.requests.size)

        viewModel.onEvent(ActivityEvent.ScreenResumed)

        assertEquals(2, repository.requests.size)

        repository.gate = null
        gate.complete(Unit)

        assertTrue(viewModel.state.value.items is ScreenState.Content)
    }

    @Test
    fun `a refresh started after a load more wins`() = runTest {
        // Догрузка ушла в сеть, а обновление стартовало позже неё и ответило
        // раньше. Ответ догрузки после этого приклеил бы к обновлённому списку
        // страницу от **предыдущего** и вернул бы устаревший курсор.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("stale", status = ActivityStatus.InProgress)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        val viewModel = ActivityViewModel(repository)

        val loadMoreGate = CompletableDeferred<Unit>()
        repository.gate = loadMoreGate
        viewModel.onEvent(ActivityEvent.LoadMore)
        assertEquals(2, repository.requests.size)

        // Обновление поверх незавершённой догрузки.
        val refreshGate = CompletableDeferred<Unit>()
        repository.gate = refreshGate
        repository.feeds[setOf(ActivitySource.Orders)] = ActivityFeed(
            items = listOf(activity("stale-page-2", status = ActivityStatus.InProgress)),
            nextPages = mapOf(ActivitySource.Orders to 2),
        )
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("fresh", status = ActivityStatus.InProgress)),
        )
        viewModel.onEvent(ActivityEvent.Refreshed)

        // Обновление отвечает первым, догрузка — уже после него. Её ответ
        // нельзя применять: и список, и курсор он вернёт к состоянию «до».
        refreshGate.complete(Unit)
        loadMoreGate.complete(Unit)
        repository.gate = null

        val state = viewModel.state.value
        assertEquals(
            listOf("fresh"),
            (state.items as ScreenState.Content).data.map(Activity::id),
        )
        // И курсор от обновления, а не от догрузки.
        assertFalse(state.hasMore)
        assertFalse(state.isRefreshing)
        assertFalse(state.isLoadingMore)
        assertNull(state.loadMoreFailure)
    }

    @Test
    fun `load more while the list is refreshing does not touch the network`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("active", status = ActivityStatus.InProgress)),
            nextPages = mapOf(ActivitySource.Orders to 1),
        )
        val viewModel = ActivityViewModel(repository)

        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.onEvent(ActivityEvent.Refreshed)
        assertEquals(2, repository.requests.size)

        // Хвост списка виден и во время обновления: его первая композиция
        // просит догрузку, а курсор у неё — от списка, который вот-вот уедет.
        viewModel.onEvent(ActivityEvent.LoadMore)

        assertEquals(2, repository.requests.size)

        repository.gate = null
        gate.complete(Unit)

        assertTrue(viewModel.state.value.items is ScreenState.Content)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `pull to refresh keeps the list on screen while it reloads`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(items = listOf(activity("o-1", ActivitySource.Orders)))
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.Refreshed)
        val state = viewModel.state.value

        assertFalse(state.isRefreshing)
        assertTrue(state.items is ScreenState.Content)
    }

    // --- Переходы ---

    @Test
    fun `a food order opens its status screen`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity("o-1", ActivitySource.Orders, target = ActivityTarget.FoodOrder("o-1")),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.ActivityClicked("Orders:o-1"))

        assertEquals(ActivityEffect.OpenFoodOrder("o-1"), viewModel.effects.first())
    }

    @Test
    fun `a cinema ticket opens its card`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity("t-1", ActivitySource.CinemaTickets, target = ActivityTarget.CinemaTicket("t-1")),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.ActivityClicked("CinemaTickets:t-1"))

        assertEquals(ActivityEffect.OpenTicket("t-1"), viewModel.effects.first())
    }

    @Test
    fun `a master appointment opens its card with the barber vertical`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity(
                    "a-1",
                    ActivitySource.MasterAppointments,
                    target = ActivityTarget.MasterAppointment("a-1"),
                ),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.ActivityClicked("MasterAppointments:a-1"))

        assertEquals(
            ActivityEffect.OpenAppointment("a-1", AppointmentVertical.Barber.name),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `a doctor appointment opens its card with the doctor vertical`() = runTest {
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity(
                    "h-1",
                    ActivitySource.DoctorAppointments,
                    target = ActivityTarget.DoctorAppointment("h-1"),
                ),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.ActivityClicked("DoctorAppointments:h-1"))

        assertEquals(
            ActivityEffect.OpenAppointment("h-1", AppointmentVertical.Doctor.name),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `an activity without a screen leads nowhere`() = runTest {
        // У брони игровых зон своего экрана ещё нет: строка не кликабельна, и
        // эффекта у неё быть не должно.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(activity("b-1", ActivitySource.GamingBookings)),
        )
        val viewModel = ActivityViewModel(repository)
        val booking = (viewModel.state.value.items as ScreenState.Content).data.single()

        assertFalse(booking.isActionable)

        viewModel.onEvent(ActivityEvent.ActivityClicked(booking.key))
        // Эффекты идут очередью: следующий доедет первым, только если тап по
        // брони не отправил своего.
        viewModel.onEvent(ActivityEvent.DiscoveryRequested)

        assertEquals(ActivityEffect.OpenDiscovery, viewModel.effects.first())
    }

    @Test
    fun `a tap on an activity that is no longer in the list is ignored`() = runTest {
        // Список мог перезагрузиться между отрисовкой и нажатием.
        val repository = FakeActivityRepository()
        repository.defaultFeed = ActivityFeed(
            items = listOf(
                activity("o-1", ActivitySource.Orders, target = ActivityTarget.FoodOrder("o-1")),
            ),
        )
        val viewModel = ActivityViewModel(repository)

        viewModel.onEvent(ActivityEvent.ActivityClicked("Orders:gone"))
        viewModel.onEvent(ActivityEvent.DiscoveryRequested)

        assertEquals(ActivityEffect.OpenDiscovery, viewModel.effects.first())
    }

    @Test
    fun `the empty state button goes to discovery`() = runTest {
        val viewModel = ActivityViewModel(FakeActivityRepository())

        viewModel.onEvent(ActivityEvent.DiscoveryRequested)

        assertEquals(ActivityEffect.OpenDiscovery, viewModel.effects.first())
    }

    private fun activity(
        id: String,
        source: ActivitySource = ActivitySource.Orders,
        status: ActivityStatus = ActivityStatus.Placed,
        at: String? = "2026-09-04T10:00:00Z",
        target: ActivityTarget = ActivityTarget.None,
    ) = Activity(
        id = id,
        source = source,
        kind = ActivityKind.FoodOrder,
        status = status,
        occurredAt = at?.let(Instant::parse),
        target = target,
    )
}
