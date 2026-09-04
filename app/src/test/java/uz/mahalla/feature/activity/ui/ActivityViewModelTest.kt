package uz.mahalla.feature.activity.ui

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
            failures = ActivitySource.entries.associateWith { ApiFailure(ApiError.Unauthorized) },
        )

        val state = ActivityViewModel(repository).state.value

        // Истёкшая сессия — не «вы ещё ничего не заказывали».
        assertEquals(ApiError.Unauthorized, (state.items as ScreenState.Error).error)
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

        viewModel.onEvent(ActivityEvent.ScreenResumed)

        // Пока приложение было в фоне, заказ могли собрать.
        assertEquals(2, repository.requests.size)
        assertTrue(viewModel.state.value.items is ScreenState.Content)
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
    fun `an activity without a screen leads nowhere`() = runTest {
        // У брони, записи и билета своих экранов ещё нет: строка не
        // кликабельна, и эффекта у неё быть не должно.
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
