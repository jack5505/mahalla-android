package uz.mahalla.core.analytics

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.analytics.di.AnalyticsModule
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.testutil.FakeAnalyticsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * «Выстрелил и забыл» (issue #169): проверяется ровно то, за что отвечает
 * трекер, — что отправка не держит вызывающего и не имеет права его уронить.
 *
 * Область собирается как в `AnalyticsModule` ([SupervisorJob]), но с
 * [CoroutineExceptionHandler]: в приложении такого обработчика нет, и всё, что
 * дошло бы до него, ушло бы в `Thread.uncaughtExceptionHandler`, то есть в
 * падение приложения. Здесь он ловушка — тесты требуют, чтобы он молчал.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsTrackerTest {

    private val repository = FakeAnalyticsRepository()
    private val escaped = mutableListOf<Throwable>()

    @Test
    fun `track returns before the request is made`() = runTest {
        val tracker = tracker()

        tracker.track(AnalyticsEvents.placeViewed("p-1"))

        // Вызывающий уже продолжил работу, а запроса ещё нет: экран не ждёт
        // аналитику и не отменяет её вместе со своей корутиной.
        assertTrue(repository.events.isEmpty())
        runCurrent()
        assertEquals(listOf(AnalyticsEvents.placeViewed("p-1")), repository.events)
    }

    @Test
    fun `a refusal of the backend does not reach the caller`() = runTest {
        repository.result = ApiResult.Failure(ApiError.Unauthorized)

        tracker().track(AnalyticsEvents.placeCalled("p-1"))
        runCurrent()

        // Отказ ушёл в лог: показывать его человеку нечего, он про аналитику
        // не просил.
        assertEquals(1, repository.events.size)
        assertTrue(escaped.isEmpty())
    }

    @Test
    fun `an exception inside the repository does not leave the coroutine`() = runTest {
        // Исключение, дошедшее до обработчика области, в приложении уронило бы
        // его целиком — из-за события аналитики.
        repository.crash = IllegalStateException("сломался конвертер")

        tracker().track(AnalyticsEvents.placeViewed("p-1"))
        runCurrent()

        assertEquals(1, repository.events.size)
        assertEquals(emptyList<Throwable>(), escaped)
    }

    @Test
    fun `one failed event does not stop the next ones`() = runTest {
        repository.crash = IllegalStateException("сломался конвертер")
        val tracker = tracker()

        tracker.track(AnalyticsEvents.placeViewed("p-1"))
        runCurrent()
        repository.crash = null
        tracker.track(AnalyticsEvents.placeCalled("p-1"))
        runCurrent()

        // Область обязана выжить: иначе аналитика замолчала бы до перезапуска.
        assertEquals(
            listOf(AnalyticsEventType.View, AnalyticsEventType.Call),
            repository.events.map(AnalyticsEvent::type),
        )
    }

    @Test
    fun `the tracker from the graph sends on a live scope of its own`() {
        // Область собирает `AnalyticsModule`, и тестов у него не было: подмени
        // её потом на `viewModelScope` — все остальные тесты остались бы
        // зелёными, а события начали бы теряться на закрытии экрана.
        // Здесь трекер берётся именно из модуля, на области из
        // `provideAnalyticsScope` (настоящий `Dispatchers.IO`, без тестового
        // диспетчера) — он обязан доставить событие сам.
        val scope = AnalyticsModule.provideAnalyticsScope()
        try {
            val tracker = AnalyticsModule.provideAnalyticsTracker(repository, scope)
            val delivered = CountDownLatch(1)
            repository.onTrack = { delivered.countDown() }

            tracker.track(AnalyticsEvents.placeViewed("p-1"))

            // Ждём сигнал о доставке, а не опрашиваем `events` по стенным
            // часам: на загруженном CI-раннере опрос мог не успеть заметить
            // результат до дедлайна и покраснеть без изменений в коде
            // (issue #228, п. 3).
            assertTrue(delivered.await(DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(AnalyticsEvents.placeViewed("p-1")), repository.events)
        } finally {
            // Область — синглтон графа: тест не должен пережить её собой,
            // иначе она утечёт до конца JVM тестового прогона.
            scope.cancel()
        }
    }

    private fun TestScope.tracker() = DefaultAnalyticsTracker(
        repository = repository,
        scope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> escaped += error },
        ),
    )

    private companion object {
        /** Отправка идёт на `Dispatchers.IO` — ждём её, а не спим наугад. */
        const val DELIVERY_TIMEOUT_MS = 5_000L
    }
}
