package uz.mahalla.feature.notifications.ui

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
import uz.mahalla.testutil.FakeNotificationsRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Бейдж непрочитанного в топбаре главной (issue #81).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsBadgeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the counter is read on open`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(4)

        val state = NotificationsBadgeViewModel(repository).state.value

        assertEquals(4, state.unreadCount)
        assertTrue(state.hasUnread)
    }

    @Test
    fun `returning to the screen re-reads the counter`() = runTest {
        val repository = FakeNotificationsRepository()
        repository.unreadCount = ApiResult.Success(2)
        val viewModel = NotificationsBadgeViewModel(repository)

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
        val viewModel = NotificationsBadgeViewModel(repository)

        repository.unreadCount = ApiResult.Failure(ApiError.NoConnection)
        viewModel.onEvent(NotificationsBadgeEvent.ScreenResumed)

        // Обнулить счётчик из-за пропавшей сети значило бы соврать, что
        // непрочитанного нет.
        assertEquals(5, viewModel.state.value.unreadCount)
    }

    @Test
    fun `a three digit count is shortened instead of overflowing the badge`() {
        assertEquals("1", badgeLabel(1))
        assertEquals("99", badgeLabel(99))
        assertEquals("99+", badgeLabel(120))
    }
}
