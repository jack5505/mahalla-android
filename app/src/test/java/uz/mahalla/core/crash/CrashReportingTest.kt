package uz.mahalla.core.crash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.testutil.FakeCrashReporter
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Проглоченные ошибки доезжают до отчётов (issue #74): отказ Keystore и отказ
 * записи в DataStore для пользователя не падение, а для эксплуатации —
 * единственный след.
 */
class CrashReportingTest {

    private val reporter = FakeCrashReporter()

    @After
    fun tearDown() {
        // Холдер процессный: без сброса прогоны влияли бы друг на друга.
        CrashReporting.reset()
    }

    @Test
    fun `failure is reported with the name of the operation`() {
        CrashReporting.install(reporter)
        val failure = IOException("no space left on device")

        val result = runCatchingCancellable { throw failure }.reportSwallowed("settings.setCity")

        assertEquals(1, reporter.reports.size)
        assertSame(failure, reporter.reports.single().error)
        assertEquals("settings.setCity", reporter.reports.single().operation)
        // Сам Result не меняется: вызывающий разбирает отказ как раньше.
        assertTrue(result.isFailure)
    }

    @Test
    fun `success is not reported`() {
        CrashReporting.install(reporter)

        val result = runCatchingCancellable { "ok" }.reportSwallowed("settings.setCity")

        assertTrue(reporter.reports.isEmpty())
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `without an installed reporter the call is harmless`() {
        // Так работают все тесты ViewModel'ей и сборка без DSN.
        val result = runCatchingCancellable { throw IOException("boom") }
            .reportSwallowed("settings.setCity")

        assertTrue(result.isFailure)
    }

    @Test
    fun `cancellation is not reported and is not swallowed`() {
        CrashReporting.install(reporter)

        val thrown = runCatching {
            runCatchingCancellable { throw CancellationException("scope closed") }
                .reportSwallowed("settings.setCity")
        }

        // Отмена корутины — не инцидент: она проходит мимо runCatchingCancellable
        // и в панели ей делать нечего.
        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun `reset returns the holder to the noop reporter`() {
        CrashReporting.install(reporter)
        CrashReporting.reset()

        CrashReporting.recordNonFatal(IOException("boom"), "settings.setCity")

        assertTrue(reporter.reports.isEmpty())
        assertNull(reporter.reports.firstOrNull())
    }
}
