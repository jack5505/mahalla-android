package uz.mahalla.core.result

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Обёртка, которой прикрыты записи в Keystore и DataStore. Главное её
 * свойство — отмена корутины остаётся отменой: обычный `runCatching` поймал бы
 * `CancellationException` и продолжил выполнять тело уже мёртвой корутины.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunCatchingCancellableTest {

    @Test
    fun `a value comes through`() {
        assertEquals("ok", runCatchingCancellable { "ok" }.getOrNull())
    }

    @Test
    fun `an error becomes a failure`() {
        val result = runCatchingCancellable { throw IOException("нет места") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        var afterBlock = false
        val started = CompletableDeferred<Unit>()

        val job = launch {
            runCatchingCancellable {
                started.complete(Unit)
                awaitCancellation()
            }
            // Сюда попадать нельзя: корутина отменена внутри блока.
            afterBlock = true
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse("тело отменённой корутины продолжило работу", afterBlock)
    }
}
