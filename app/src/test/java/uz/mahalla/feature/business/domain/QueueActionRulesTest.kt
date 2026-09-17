package uz.mahalla.feature.business.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.queue.domain.WalkInStatus
import java.time.Instant

/**
 * Правила действий над талоном (задача 12.2), в том числе «вызвать
 * следующего».
 */
class QueueActionRulesTest {

    @Test
    fun `a pending ticket is accepted or declined`() {
        assertEquals(
            listOf(QueueAction.Accept, QueueAction.Decline),
            QueueActionRules.available(WalkInStatus.Pending),
        )
    }

    @Test
    fun `a waiting ticket is called or declined`() {
        assertEquals(
            listOf(QueueAction.Start, QueueAction.Decline),
            QueueActionRules.available(WalkInStatus.Waiting),
        )
        assertEquals(
            listOf(QueueAction.Start, QueueAction.Decline),
            QueueActionRules.available(WalkInStatus.Accepted),
        )
    }

    @Test
    fun `a ticket in the chair is only completed`() {
        assertEquals(
            listOf(QueueAction.Complete),
            QueueActionRules.available(WalkInStatus.InChair),
        )
    }

    /** Мяч на стороне клиента: он ещё не ответил на предложенное время. */
    @Test
    fun `a counter-offered ticket gives no actions`() {
        assertTrue(QueueActionRules.available(WalkInStatus.CounterOffered).isEmpty())
    }

    @Test
    fun `a finished ticket gives no actions`() {
        listOf(
            WalkInStatus.Completed,
            WalkInStatus.Declined,
            WalkInStatus.Cancelled,
            WalkInStatus.NoShow,
            WalkInStatus.Expired,
        ).forEach { status ->
            assertTrue(QueueActionRules.available(status).isEmpty())
            assertTrue(QueueActionRules.isFinished(status))
        }
    }

    /**
     * Отличие от клиентского `WalkInStatusFlow.canCancel`, где незнакомый
     * статус разрешает отмену: клиенту нельзя запирать выход, а заведению
     * нельзя предлагать действие, последствий которого никто не знает.
     */
    @Test
    fun `an unknown status gives no actions to the place`() {
        assertTrue(QueueActionRules.available(WalkInStatus.Unknown).isEmpty())
        assertFalse(QueueActionRules.isFinished(WalkInStatus.Unknown))
    }

    @Test
    fun `the next in line is the one closest to the chair`() {
        val next = QueueActionRules.nextInLine(
            listOf(
                entry("t-1", WalkInStatus.Waiting, position = 3),
                entry("t-2", WalkInStatus.Waiting, position = 1),
                entry("t-3", WalkInStatus.Pending),
            ),
        )

        assertEquals("t-2", next?.id)
    }

    /** Мастер один: пока кресло занято, вызывать некого. */
    @Test
    fun `there is no next while somebody is in the chair`() {
        val next = QueueActionRules.nextInLine(
            listOf(
                entry("t-1", WalkInStatus.InChair),
                entry("t-2", WalkInStatus.Waiting, position = 1),
            ),
        )

        assertNull(next)
    }

    /**
     * Талон без позиции идёт в хвост: считать `null` нулём значило бы вызывать
     * раньше всех того, о ком сервер позиции не сообщил.
     */
    @Test
    fun `a ticket without a position goes last, ordered by time`() {
        val next = QueueActionRules.nextInLine(
            listOf(
                entry("t-late", WalkInStatus.Waiting, createdAt = "2026-09-09T10:00:00Z"),
                entry("t-early", WalkInStatus.Waiting, createdAt = "2026-09-09T09:00:00Z"),
                entry("t-positioned", WalkInStatus.Waiting, position = 5),
            ),
        )

        assertEquals("t-positioned", next?.id)
    }

    @Test
    fun `a queue of only pending tickets has nobody to call`() {
        val next = QueueActionRules.nextInLine(
            listOf(entry("t-1", WalkInStatus.Pending), entry("t-2", WalkInStatus.Pending)),
        )

        assertNull(next)
    }

    @Test
    fun `an empty queue has no next`() {
        assertNull(QueueActionRules.nextInLine(emptyList()))
    }

    private fun entry(
        id: String,
        status: WalkInStatus,
        position: Int? = null,
        createdAt: String? = null,
    ) = QueueEntry(
        id = id,
        userName = id,
        status = status,
        queuePosition = position,
        createdAt = createdAt?.let(Instant::parse),
    )
}
