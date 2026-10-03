package uz.mahalla.feature.security.data

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.security.domain.AppLockManager
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeSessionStore
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Подписка замка на жизненный цикл процесса (issue #102, issue #347).
 *
 * `install()` вешает наблюдателя на синглтон `ProcessLifecycleOwner`,
 * недоступный тесту напрямую — поэтому здесь наблюдатель добавляется на свой
 * `LifecycleRegistry`: переходы `ON_STOP`/`ON_START` тот вызывает точно так
 * же, как их вызвал бы настоящий процесс.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLockObserverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = MovableClock()
    private val sessionStore = FakeSessionStore(Session("a-1", "r-1"))
    private val pinStorage = FakePinStorage(initialPin = "123456")
    private val manager = AppLockManager(sessionStore = sessionStore, pinStorage = pinStorage, clock = clock)
    private val observer = AppLockObserver(manager)
    private val owner = FakeLifecycleOwner()

    @Test
    fun `a stop-start pair longer than the grace period locks the app`() {
        owner.registry.addObserver(observer)
        settle()

        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED // onStop -> onBackground()
        clock.advance(AppLockManager.GRACE.plusSeconds(1))
        owner.registry.currentState = Lifecycle.State.STARTED // onStart -> onForeground()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.locked.value)
    }

    @Test
    fun `a short trip through onStop and onStart does not lock`() {
        owner.registry.addObserver(observer)
        settle()

        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.CREATED
        clock.advance(Duration.ofSeconds(5))
        owner.registry.currentState = Lifecycle.State.STARTED
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(manager.locked.value)
    }

    /**
     * Свежий [AppLockManager] сам по себе холодный старт (см. его тесты) —
     * без этого шага первый же переход ниже запирал бы замок по отметке
     * холодного старта, а не по паре onStop/onStart, которую здесь и
     * проверяем.
     */
    private fun settle() {
        runBlocking { manager.onForeground() }
        manager.unlock()
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Часы, которые двигает тест: замок меряет фон в реальном времени. */
    private class MovableClock : Clock() {
        private var now: Instant = Instant.parse("2026-09-04T12:00:00Z")

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
}
