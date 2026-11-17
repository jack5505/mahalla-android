package uz.mahalla.feature.notifications.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.notifications.push.NotificationsBadgeSignal
import uz.mahalla.testutil.FakeNotificationsRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Бейдж непрочитанного в топбаре главной (issue #81).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsBadgeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val signal = NotificationsBadgeSignal()

    @Test
    fun `the counter is read on open`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(4)

        val state = NotificationsBadgeViewModel(repository, signal).state.value

        assertEquals(4, state.unreadCount)
        assertTrue(state.hasUnread)
    }

    @Test
    fun `returning to the screen re-reads the counter`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(2)
        val viewModel = NotificationsBadgeViewModel(repository, signal)

        // На соседнем экране нажали «прочитать всё» — старт ViewModel об этом
        // не знает, бейдж обновляется именно на возврате.
        repository.unreadCount = ApiResult.Success(0)
        viewModel.onEvent(NotificationsBadgeEvent.ScreenResumed)

        assertEquals(0, viewModel.state.value.unreadCount)
        assertFalse(viewModel.state.value.hasUnread)
        assertEquals(2, repository.unreadCalls)
    }

    @Test
    fun `a failed request leaves the badge as it was`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(5)
        val viewModel = NotificationsBadgeViewModel(repository, signal)

        repository.unreadCount = ApiResult.Failure(ApiError.NoConnection)
        viewModel.onEvent(NotificationsBadgeEvent.ScreenResumed)

        // Обнулить счётчик из-за пропавшей сети значило бы соврать, что
        // непрочитанного нет.
        assertEquals(5, viewModel.state.value.unreadCount)
    }

    @Test
    fun `a push received while the app is open re-reads the counter`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(1)
        val viewModel = NotificationsBadgeViewModel(repository, signal)

        // Пуш пришёл, пока приложение открыто (issue #429) — без сигнала
        // счётчик остался бы прежним до следующего ON_RESUME.
        repository.unreadCount = ApiResult.Success(7)
        signal.notifyPushReceived()

        assertEquals(7, viewModel.state.value.unreadCount)
        assertTrue(viewModel.state.value.hasUnread)
        assertEquals(2, repository.unreadCalls)
    }

    @Test
    fun `a push received during an in-flight request is not lost`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(2)
        val gate = CompletableDeferred<Unit>()
        repository.unreadCountGate = gate
        val viewModel = NotificationsBadgeViewModel(repository, signal)

        // Пуш приходит, пока первый запрос ещё летит: первый ответ всё
        // равно принесёт устаревшее значение, сигнал не должен потеряться.
        repository.unreadCount = ApiResult.Success(9)
        signal.notifyPushReceived()
        gate.complete(Unit)

        assertEquals(9, viewModel.state.value.unreadCount)
        assertEquals(2, repository.unreadCalls)
    }

    @Test
    fun `a three digit count is shortened instead of overflowing the badge`() {
        assertEquals("1", badgeLabel(1))
        assertEquals("99", badgeLabel(99))
        assertEquals("99+", badgeLabel(120))
    }
}
