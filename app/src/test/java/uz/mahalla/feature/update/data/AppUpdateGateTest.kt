package uz.mahalla.feature.update.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.update.domain.AppUpdate
import uz.mahalla.feature.update.domain.UpdateDecision
import uz.mahalla.testutil.FakeAppVersionRepository

/**
 * Гейт обновления (issue #80): проверка идёт один раз за запуск, под
 * держащимся splash'ем.
 *
 * Главное свойство — **отказ проверки не запирает приложение**: упавший бэкенд
 * не должен превращаться в кирпич на всех устройствах сразу.
 */
class AppUpdateGateTest {

    @Test
    fun `a required update reaches the start of the app`() = runTest {
        val update = AppUpdate(versionName = "1.4.0")
        val gate = gate(FakeAppVersionRepository(ApiResult.Success(UpdateDecision.Required(update))))

        assertEquals(UpdateDecision.Required(update), gate.check())
        assertEquals("решение переживает проверку", UpdateDecision.Required(update), gate.current())
    }

    @Test
    fun `a silent backend does not lock the app`() = runTest {
        val gate = gate(FakeAppVersionRepository.failing(ApiError.NoConnection))

        assertEquals(UpdateDecision.None, gate.check())
    }

    @Test
    fun `a server error does not lock the app either`() = runTest {
        val gate = gate(FakeAppVersionRepository.failing(ApiError.Http(500, "Internal Error")))

        assertEquals(UpdateDecision.None, gate.check())
    }

    @Test
    fun `a slow backend does not hold the splash`() = runTest {
        // Без собственного бюджета недоступный сервер держал бы запуск до
        // сетевых таймаутов — 15 сек на соединение плюс 30 на чтение.
        val repository = FakeAppVersionRepository(
            answer = ApiResult.Success(UpdateDecision.Required(AppUpdate())),
            delayMillis = AppUpdateGate.CHECK_BUDGET_MILLIS + 1,
        )

        assertEquals(UpdateDecision.None, gate(repository).check())
    }

    @Test
    fun `an answer inside the budget still arrives`() = runTest {
        val repository = FakeAppVersionRepository(
            answer = ApiResult.Success(UpdateDecision.Required(AppUpdate())),
            delayMillis = AppUpdateGate.CHECK_BUDGET_MILLIS - 1,
        )

        assertEquals(UpdateDecision.Required(AppUpdate()), gate(repository).check())
    }

    @Test
    fun `the backend is asked once per launch`() = runTest {
        // Настройки — живой flow, и resolveStart мог бы позвать проверку на
        // каждой эмиссии.
        val repository = FakeAppVersionRepository()
        val gate = gate(repository)

        gate.check()
        gate.check()

        assertEquals(1, repository.checkCount)
    }

    @Test
    fun `later reports the skip and forgets the decision`() = runTest {
        val repository = FakeAppVersionRepository(
            ApiResult.Success(
                UpdateDecision.Suggested(AppUpdate(versionId = "v-1", remainingSkips = 2)),
            ),
        )
        val gate = gate(repository)
        gate.check()

        gate.skip()

        assertEquals(listOf("v-1"), repository.skipped)
        assertEquals("экран отработал", UpdateDecision.None, gate.current())
    }

    @Test
    fun `later works even when the skip is refused`() = runTest {
        // До входа `skip` отвечает 401: пропуски бэкенд считает пользователю.
        // Держать человека на экране из-за служебного запроса нельзя.
        val repository = FakeAppVersionRepository(
            answer = ApiResult.Success(UpdateDecision.Suggested(AppUpdate(versionId = "v-1"))),
            skipAnswer = ApiResult.Failure(ApiError.Unauthorized),
        )
        val gate = gate(repository)
        gate.check()

        gate.skip()

        assertEquals(UpdateDecision.None, gate.current())
    }

    @Test
    fun `a slow skip does not hold the screen`() = runTest {
        val repository = FakeAppVersionRepository(
            answer = ApiResult.Success(UpdateDecision.Suggested(AppUpdate(versionId = "v-1"))),
        )
        val gate = gate(repository)
        gate.check()
        repository.delayMillis = AppUpdateGate.SKIP_BUDGET_MILLIS + 1

        gate.skip()

        assertEquals(UpdateDecision.None, gate.current())
    }

    @Test
    fun `nothing is reported when there is no version to skip`() = runTest {
        // Сервер не прислал versionId — сообщать о пропуске нечего, но экран
        // всё равно обязан закрыться.
        val repository = FakeAppVersionRepository(
            ApiResult.Success(UpdateDecision.Suggested(AppUpdate(versionId = null))),
        )
        val gate = gate(repository)
        gate.check()

        gate.skip()

        assertTrue(repository.skipped.isEmpty())
        assertEquals(UpdateDecision.None, gate.current())
    }

    private fun gate(repository: FakeAppVersionRepository) = AppUpdateGate(repository)
}
