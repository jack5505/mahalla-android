package uz.mahalla.feature.security.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.data.prefs.Session
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeSessionStore
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Замок приложения (issue #102).
 *
 * Самая аккуратная часть задачи: ошибка здесь запирает человека вне
 * приложения, поэтому каждый случай «нечем открыть» проверяется отдельно.
 */
class AppLockManagerTest {

    private val sessionStore = FakeSessionStore(Session("a-1", "r-1"))
    private val pinStorage = FakePinStorage(initialPin = "123456")
    private val clock = MovableClock()

    @Test
    fun `short trip out of the app does not lock`() = runTest {
        val manager = manager()

        manager.onBackground()
        clock.advance(Duration.ofSeconds(5))
        manager.onForeground()

        // Код из SMS копируют в другом приложении, вход через Telegram уходит
        // в Telegram, оплата — в браузер: PIN на возврате оттуда не даёт
        // человеку закончить дело.
        assertFalse(manager.locked.value)
    }

    @Test
    fun `long absence locks the app`() = runTest {
        val manager = manager()

        manager.onBackground()
        clock.advance(AppLockManager.GRACE.plusSeconds(1))
        manager.onForeground()

        assertTrue(manager.locked.value)
    }

    @Test
    fun `foreground without a preceding background changes nothing`() = runTest {
        val manager = manager()

        // Первый запуск: `onStart` приходит, а фона до него не было.
        manager.onForeground()

        assertFalse(manager.locked.value)
    }

    @Test
    fun `clock moved backwards locks instead of trusting the interval`() = runTest {
        val manager = manager()

        manager.onBackground()
        clock.advance(Duration.ofHours(-3))
        manager.onForeground()

        // Отрицательный интервал — не «вернулись мгновенно», а «измерить не
        // удалось».
        assertTrue(manager.locked.value)
    }

    @Test
    fun `no session means nothing to lock`() = runTest {
        sessionStore.clear()
        val manager = manager()

        manager.onBackground()
        clock.advance(Duration.ofHours(1))
        manager.onForeground()

        // Онбординг запирать нечем и незачем: вводить на замке нечего.
        assertFalse(manager.locked.value)
    }

    @Test
    fun `no local pin copy means the lock cannot be opened offline`() = runTest {
        val manager = manager(pinStorage = FakePinStorage(initialPin = null))

        manager.onBackground()
        clock.advance(Duration.ofHours(1))
        manager.onForeground()

        // Замок, который открыть нечем, — это и есть запирание пользователя
        // вне приложения.
        assertFalse(manager.locked.value)
    }

    @Test
    fun `storage failure does not arm the lock`() = runTest {
        val failing = FakePinStorage(initialPin = "123456").apply { failure = IOException("no") }
        val manager = manager(pinStorage = failing)

        manager.onBackground()
        clock.advance(Duration.ofHours(1))
        manager.onForeground()

        assertFalse(manager.locked.value)
    }

    @Test
    fun `unlock clears the lock and the pending background mark`() = runTest {
        val manager = manager()
        manager.onBackground()
        clock.advance(Duration.ofHours(1))
        manager.onForeground()
        assertTrue(manager.locked.value)

        manager.unlock()

        assertFalse(manager.locked.value)
        // Возврат на передний план после разблокировки не должен запирать
        // снова по той же отметке фона.
        manager.onForeground()
        assertFalse(manager.locked.value)
    }

    @Test
    fun `lockNow locks without waiting for the background`() = runTest {
        val manager = manager()

        manager.lockNow()

        assertTrue(manager.locked.value)
    }

    @Test
    fun `disarm survives a later foreground`() = runTest {
        val manager = manager()
        manager.lockNow()

        manager.disarm()

        assertFalse(manager.locked.value)
        manager.onForeground()
        assertFalse(manager.locked.value)
    }

    @Test
    fun `already locked app does not re-evaluate on foreground`() = runTest {
        val manager = manager()
        manager.lockNow()
        // Сессию отозвали, пока экран блокировки был открыт: замок обязан
        // остаться, а не раскрыться из-за того, что открывать больше нечего.
        sessionStore.clear()

        manager.onBackground()
        clock.advance(Duration.ofHours(1))
        manager.onForeground()

        assertTrue(manager.locked.value)
    }

    private fun manager(
        pinStorage: FakePinStorage = this.pinStorage,
    ) = AppLockManager(sessionStore = sessionStore, pinStorage = pinStorage, clock = clock)

    /** Часы, которые двигает тест: замок меряет фон в реальном времени. */
    private class MovableClock : Clock() {
        private var now: Instant = Instant.parse("2026-09-04T12:00:00Z")

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
