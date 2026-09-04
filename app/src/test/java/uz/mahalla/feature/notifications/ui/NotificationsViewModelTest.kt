package uz.mahalla.feature.notifications.ui

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
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.notifications.domain.AppNotification
import uz.mahalla.feature.notifications.domain.NotificationPage
import uz.mahalla.feature.notifications.domain.NotificationType
import uz.mahalla.testutil.FakeNotificationsRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Instant

/**
 * Центр уведомлений (issue #81): список и счётчик непрочитанного — две разные
 * ручки, и отказ одной не должен ломать другую.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the list and the unread count are loaded on open`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(3)

        val state = NotificationsViewModel(repository).state.value

        assertEquals(
            listOf("n-1"),
            (state.items as ScreenState.Content).data.map(AppNotification::id),
        )
        assertEquals(3, state.unreadCount)
        assertTrue(state.canMarkAllRead)
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `an empty list is not an error`() = runTest {
        val repository = FakeNotificationsRepository()

        val state = NotificationsViewModel(repository).state.value

        assertTrue(state.items is ScreenState.Empty)
        // Читать нечего — кнопки «прочитать всё» в топбаре нет.
        assertFalse(state.canMarkAllRead)
    }

    @Test
    fun `a broken counter does not hide the list`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Failure(ApiError.NoConnection)

        val state = NotificationsViewModel(repository).state.value

        assertTrue(state.items is ScreenState.Content)
        assertEquals(0, state.unreadCount)
    }

    @Test
    fun `a broken list keeps the server message`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Forbidden,
                server = ServerError(httpCode = 403, message = "Joylashuv ruxsatini yoqing"),
            ),
        )

        val state = NotificationsViewModel(repository).state.value

        assertEquals(
            "Joylashuv ruxsatini yoqing",
            (state.items as ScreenState.Error).failure.serverMessage,
        )
        assertFalse(state.hasMore)
    }

    @Test
    fun `returning to the screen reloads the list and the counter`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        val viewModel = NotificationsViewModel(repository)

        // Уведомление пришло, пока приложение было в фоне.
        repository.defaultPage = page(
            listOf(notification("n-2"), notification("n-1")),
            hasMore = false,
        )
        repository.unreadCount = ApiResult.Success(1)
        viewModel.onEvent(NotificationsEvent.ScreenResumed)

        val state = viewModel.state.value
        assertEquals(
            listOf("n-2", "n-1"),
            (state.items as ScreenState.Content).data.map(AppNotification::id),
        )
        assertEquals(1, state.unreadCount)
        assertEquals(2, repository.unreadCalls)
    }

    @Test
    fun `the next page is appended without duplicates`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.pages[0] = page(listOf(notification("n-1")), hasMore = true)
        // Список пополнился между запросами: одна и та же запись приехала
        // дважды, а дубликат ключа роняет LazyColumn.
        repository.pages[1] = page(listOf(notification("n-1"), notification("n-2")), hasMore = false)
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(
            listOf("n-1", "n-2"),
            (state.items as ScreenState.Content).data.map(AppNotification::id),
        )
        assertFalse(state.hasMore)
        assertEquals(listOf(0, 1), repository.requestedPages)
    }

    @Test
    fun `a failed load more keeps the list and explains itself`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.pages[0] = page(listOf(notification("n-1")), hasMore = true)
        repository.pages[1] = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(1, (state.items as ScreenState.Content).data.size)
        assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `mark all read marks the loaded pages and clears the badge`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(notification("n-1"), notification("n-2", isRead = true)),
            hasMore = false,
        )
        repository.unreadCount = ApiResult.Success(1)
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.MarkAllRead)

        val state = viewModel.state.value
        assertTrue((state.items as ScreenState.Content).data.all(AppNotification::isRead))
        assertEquals(0, state.unreadCount)
        assertFalse(state.canMarkAllRead)
        assertNull(state.actionFailure)
        // Перезагрузки нет: сервер подтвердил успех, а она сбросила бы
        // догруженный хвост списка к первой странице.
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `mark all read is not sent when there is nothing to read`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1", isRead = true)), hasMore = false)
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.MarkAllRead)

        assertEquals(0, repository.markAllReadCalls)
    }

    @Test
    fun `a failed mark all read keeps the list and the badge`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        repository.markAllRead = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Http(500, "Internal Server Error"),
                server = ServerError(httpCode = 500, message = "Xatolik yuz berdi"),
            ),
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.MarkAllRead)

        val state = viewModel.state.value
        assertEquals("Xatolik yuz berdi", state.actionFailure?.serverMessage)
        assertFalse((state.items as ScreenState.Content).data.single().isRead)
        assertEquals(1, state.unreadCount)
        assertFalse(state.isMarkingRead)
    }

    @Test
    fun `an opened notification is marked read in place and the badge drops by one`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(notification("n-1"), notification("n-2")),
            hasMore = false,
        )
        repository.unreadCount = ApiResult.Success(2)
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        val state = viewModel.state.value
        val items = (state.items as ScreenState.Content).data
        assertTrue(items.single { it.id == "n-1" }.isRead)
        // Соседнее уведомление не трогаем: прочитали одно.
        assertFalse(items.single { it.id == "n-2" }.isRead)
        assertEquals(1, state.unreadCount)
        assertEquals(listOf("n-1"), repository.markReadIds)
        assertNull(state.actionFailure)
        // Перезапроса нет: он сбросил бы догруженный хвост к первой странице.
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `a notification without a target is marked read too`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(notification("n-1", type = NotificationType.PromotionCreated, entityId = null)),
            hasMore = false,
        )
        repository.unreadCount = ApiResult.Success(1)
        val viewModel = NotificationsViewModel(repository)

        // Уведомление остаётся текстом в списке, но человек его уже прочёл.
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        assertEquals(listOf("n-1"), repository.markReadIds)
        assertEquals(0, viewModel.state.value.unreadCount)
    }

    @Test
    fun `an already read notification is not sent to the server again`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(notification("n-1", isRead = true)),
            hasMore = false,
        )
        val viewModel = NotificationsViewModel(repository)

        // Второй тап по прочитанному — и по только что прочитанному тоже:
        // гасить погашенное незачем.
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        assertTrue(repository.markReadIds.isEmpty())
    }

    @Test
    fun `a second tap does not send a second mark read request`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        val gate = CompletableDeferred<Unit>()
        repository.markReadGate = gate
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))
        // Ответ ещё не пришёл, а человек нажал второй раз: на экране строка
        // уже прочитана, гасить её повторно нечем.
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))
        gate.complete(Unit)

        assertEquals(listOf("n-1"), repository.markReadIds)
        assertEquals(0, viewModel.state.value.unreadCount)
    }

    @Test
    fun `a failed mark read rolls the notification back and explains itself`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        repository.markRead = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Http(500, "Internal Server Error"),
                server = ServerError(httpCode = 500, message = "Xatolik yuz berdi"),
            ),
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        val state = viewModel.state.value
        assertFalse((state.items as ScreenState.Content).data.single().isRead)
        assertEquals(1, state.unreadCount)
        assertEquals("Xatolik yuz berdi", state.actionFailure?.serverMessage)
    }

    @Test
    fun `a failed mark read is retried by the same notification`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        repository.markRead = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = NotificationsViewModel(repository)
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        repository.markRead = ApiResult.Success(Unit)
        viewModel.onEvent(NotificationsEvent.RetryAction)

        val state = viewModel.state.value
        assertTrue((state.items as ScreenState.Content).data.single().isRead)
        assertEquals(0, state.unreadCount)
        assertNull(state.actionFailure)
        assertEquals(listOf("n-1", "n-1"), repository.markReadIds)
        // Повтор отметки одного уведомления не превращается в «прочитать всё».
        assertEquals(0, repository.markAllReadCalls)
    }

    @Test
    fun `a failed mark all read is retried by the same button`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        repository.markAllRead = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = NotificationsViewModel(repository)
        viewModel.onEvent(NotificationsEvent.MarkAllRead)

        repository.markAllRead = ApiResult.Success(Unit)
        viewModel.onEvent(NotificationsEvent.RetryAction)

        assertEquals(2, repository.markAllReadCalls)
        assertTrue(repository.markReadIds.isEmpty())
        assertEquals(0, viewModel.state.value.unreadCount)
    }

    @Test
    fun `a late failure does not resurrect what the server already called read`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(listOf(notification("n-1")), hasMore = false)
        repository.unreadCount = ApiResult.Success(1)
        repository.markRead = ApiResult.Failure(ApiError.NoConnection)
        val gate = CompletableDeferred<Unit>()
        repository.markReadGate = gate
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))
        // Пока отказ ехал, список перезапросили — и сервер сказал, что
        // уведомление прочитано. Откат вернул бы бейдж вверх на пустом месте.
        repository.defaultPage = page(listOf(notification("n-1", isRead = true)), hasMore = false)
        repository.unreadCount = ApiResult.Success(0)
        repository.markReadGate = null
        viewModel.onEvent(NotificationsEvent.ScreenResumed)
        gate.complete(Unit)

        val state = viewModel.state.value
        assertTrue((state.items as ScreenState.Content).data.single().isRead)
        assertEquals(0, state.unreadCount)
        // Причина отказа при этом всё равно показывается.
        assertEquals(ApiError.NoConnection, state.actionFailure?.error)
    }

    @Test
    fun `an order notification opens the order status screen`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(
                notification(
                    id = "n-1",
                    type = NotificationType.OrderStatusUpdated,
                    entityId = "o-42",
                ),
            ),
            hasMore = false,
        )
        val viewModel = NotificationsViewModel(repository)

        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))

        assertEquals(NotificationsEffect.OpenOrder("o-42"), viewModel.effects.first())
    }

    @Test
    fun `a notification without a target does not navigate anywhere`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.defaultPage = page(
            listOf(notification(id = "n-1", type = NotificationType.PromotionCreated)),
            hasMore = false,
        )
        val viewModel = NotificationsViewModel(repository)

        // Незнакомая цель не роняет экран и никуда не уводит: список и есть
        // конечный экран.
        viewModel.onEvent(NotificationsEvent.NotificationClicked("n-1"))
        viewModel.onEvent(NotificationsEvent.NotificationClicked("no-such-id"))

        assertTrue(viewModel.state.value.items is ScreenState.Content)
    }

    private fun page(items: List<AppNotification>, hasMore: Boolean) =
        ApiResult.Success(NotificationPage(items = items, hasMore = hasMore))

    private fun notification(
        id: String,
        type: NotificationType = NotificationType.PromotionCreated,
        entityId: String? = "e-1",
        isRead: Boolean = false,
    ) = AppNotification(
        id = id,
        title = "Bildirishnoma",
        body = null,
        type = type,
        entityId = entityId,
        isRead = isRead,
        createdAt = Instant.parse("2026-08-31T09:12:00Z"),
    )
}
