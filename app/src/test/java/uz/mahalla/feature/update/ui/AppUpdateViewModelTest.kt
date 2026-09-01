package uz.mahalla.feature.update.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.update.data.AppUpdateGate
import uz.mahalla.feature.update.domain.AppUpdate
import uz.mahalla.feature.update.domain.UpdateDecision
import uz.mahalla.testutil.FakeAppVersionRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Экран обновления (issue #80).
 *
 * Проверяются оба режима — блокирующий и мягкий — и то, что из блокирующего
 * нельзя выйти даже событием, пришедшим мимо кнопки.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `a required update leaves no way forward`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(UpdateDecision.Required(UPDATE))

        val state = viewModel.state.value
        assertTrue(state.blocking)
        assertEquals(UPDATE, state.update)
    }

    @Test
    fun `later is ignored on a required update`() = runTest(mainDispatcherRule.dispatcher) {
        // Кнопки «Позже» на блокирующем экране нет, но полагаться на
        // ненарисованную кнопку для выхода из блокировки нельзя.
        val repository = FakeAppVersionRepository(
            ApiResult.Success(UpdateDecision.Required(UPDATE)),
        )
        val viewModel = viewModel(repository = repository)

        viewModel.onEvent(AppUpdateEvent.LaterRequested)

        assertFalse(viewModel.state.value.skipping)
        assertTrue(repository.skipped.isEmpty())
    }

    @Test
    fun `later reports the skip and continues into the app`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppVersionRepository(
                ApiResult.Success(UpdateDecision.Suggested(UPDATE)),
            )
            val viewModel = viewModel(repository = repository)
            assertFalse(viewModel.state.value.blocking)

            viewModel.onEvent(AppUpdateEvent.LaterRequested)

            assertEquals(AppUpdateEffect.Continue, viewModel.effects.first())
            assertEquals(listOf("v-1"), repository.skipped)
        }

    @Test
    fun `a second tap on later does not start a second request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppVersionRepository(
                answer = ApiResult.Success(UpdateDecision.Suggested(UPDATE)),
            )
            val viewModel = viewModel(repository = repository)
            repository.delayMillis = 1_000

            viewModel.onEvent(AppUpdateEvent.LaterRequested)
            viewModel.onEvent(AppUpdateEvent.LaterRequested)

            assertTrue(viewModel.state.value.skipping)
            assertEquals(listOf("v-1"), repository.skipped)
        }

    @Test
    fun `the update button opens the store link the server sent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(UpdateDecision.Required(UPDATE))

            viewModel.onEvent(AppUpdateEvent.UpdateRequested)

            assertEquals(AppUpdateEffect.OpenStore(UPDATE.storeUrl!!), viewModel.effects.first())
        }

    @Test
    fun `a store that cannot be opened is explained, not silent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(UpdateDecision.Required(UPDATE))

            viewModel.onEvent(AppUpdateEvent.StoreOpenFailed)
            assertTrue(viewModel.state.value.storeFailed)

            // Новая попытка снимает прошлую неудачу: магазин мог как раз
            // появиться на устройстве.
            viewModel.onEvent(AppUpdateEvent.UpdateRequested)
            assertFalse(viewModel.state.value.storeFailed)
        }

    @Test
    fun `a screen without a decision is not a dead end`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Процесс пережил экран (смерть процесса с восстановлением стека)
            // либо «Позже» уже нажали: показывать нечего, тем более
            // блокирующе.
            val viewModel = AppUpdateViewModel(AppUpdateGate(FakeAppVersionRepository()))

            assertEquals(AppUpdateEffect.Continue, viewModel.effects.first())
        }

    private suspend fun viewModel(
        decision: UpdateDecision = UpdateDecision.Suggested(UPDATE),
        repository: FakeAppVersionRepository = FakeAppVersionRepository(
            ApiResult.Success(decision),
        ),
    ): AppUpdateViewModel {
        val gate = AppUpdateGate(repository)
        gate.check()
        return AppUpdateViewModel(gate)
    }

    private companion object {
        val UPDATE = AppUpdate(
            versionId = "v-1",
            versionName = "1.4.0",
            storeUrl = "https://play.google.com/store/apps/details?id=uz.mahalla",
            remainingSkips = 2,
        )
    }
}
