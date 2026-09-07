package uz.mahalla.feature.queue.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Домен очереди (issue #96): разбор состояний, переходы и правило свежести
 * чисел очереди.
 *
 * Всё это чистые функции — и должны ими остаться: цепочку этапов и «можно ли
 * отменить» иначе пришлось бы проверять через экран.
 */
class WalkInTest {

    @Test
    fun `every status of the contract is recognized`() {
        val values = listOf(
            "PENDING" to WalkInStatus.Pending,
            "ACCEPTED" to WalkInStatus.Accepted,
            "DECLINED" to WalkInStatus.Declined,
            "COUNTER_OFFERED" to WalkInStatus.CounterOffered,
            "WAITING" to WalkInStatus.Waiting,
            "IN_CHAIR" to WalkInStatus.InChair,
            "COMPLETED" to WalkInStatus.Completed,
            "CANCELLED" to WalkInStatus.Cancelled,
            "NO_SHOW" to WalkInStatus.NoShow,
            "EXPIRED" to WalkInStatus.Expired,
        )

        values.forEach { (api, expected) ->
            assertEquals(expected, WalkInStatus.fromApi(api))
        }
    }

    @Test
    fun `case, spaces and a dash instead of an underscore are tolerated`() {
        assertEquals(WalkInStatus.InChair, WalkInStatus.fromApi(" in-chair "))
        assertEquals(WalkInStatus.NoShow, WalkInStatus.fromApi("no_show"))
    }

    @Test
    fun `an unknown status does not break the screen`() {
        // Состояния талона меняет заведение из своей панели, и новое значение
        // может приехать раньше релиза приложения.
        assertEquals(WalkInStatus.Unknown, WalkInStatus.fromApi("ON_THE_WAY"))
        assertEquals(WalkInStatus.Unknown, WalkInStatus.fromApi(null))
        assertEquals(WalkInStatus.Unknown, WalkInStatus.fromApi(""))
    }

    @Test
    fun `final statuses are the ones that will not change`() {
        listOf(
            WalkInStatus.Completed,
            WalkInStatus.Cancelled,
            WalkInStatus.Declined,
            WalkInStatus.NoShow,
            WalkInStatus.Expired,
        ).forEach { assertTrue(it.name, WalkInStatusFlow.isFinal(it)) }

        listOf(
            WalkInStatus.Pending,
            WalkInStatus.Accepted,
            WalkInStatus.CounterOffered,
            WalkInStatus.Waiting,
            WalkInStatus.InChair,
        ).forEach { assertFalse(it.name, WalkInStatusFlow.isFinal(it)) }
    }

    @Test
    fun `an unknown status is not considered finished`() {
        // «Неизвестно, чем кончилось» — не «кончилось»: объявить талон
        // закрытым по незнакомому значению значит потерять его из виду.
        assertFalse(WalkInStatusFlow.isFinal(WalkInStatus.Unknown))
        assertTrue(WalkInStatusFlow.isActive(WalkInStatus.Unknown))
    }

    @Test
    fun `cancelling is possible until the master starts`() {
        listOf(
            WalkInStatus.Pending,
            WalkInStatus.Accepted,
            WalkInStatus.CounterOffered,
            WalkInStatus.Waiting,
            // Незнакомое состояние не должно запирать человека в очереди.
            WalkInStatus.Unknown,
        ).forEach { assertTrue(it.name, WalkInStatusFlow.canCancel(it)) }

        // В кресле отмена — разговор с мастером, а не кнопка в приложении.
        assertFalse(WalkInStatusFlow.canCancel(WalkInStatus.InChair))
        assertFalse(WalkInStatusFlow.canCancel(WalkInStatus.Completed))
        assertFalse(WalkInStatusFlow.canCancel(WalkInStatus.Cancelled))
    }

    @Test
    fun `stages describe the happy path only`() {
        assertEquals(
            listOf(
                WalkInStatus.Pending,
                WalkInStatus.Accepted,
                WalkInStatus.Waiting,
                WalkInStatus.InChair,
                WalkInStatus.Completed,
            ),
            WalkInStatusFlow.stages(),
        )
    }

    @Test
    fun `statuses outside the chain do not draw progress`() {
        // Подсвеченный «ждёте» под надписью «мастер отказал» — противоречие.
        listOf(
            WalkInStatus.Declined,
            WalkInStatus.Cancelled,
            WalkInStatus.NoShow,
            WalkInStatus.Expired,
            WalkInStatus.CounterOffered,
            WalkInStatus.Unknown,
        ).forEach {
            assertFalse(it.name, WalkInStatusFlow.showsStages(it))
            assertEquals(-1, WalkInStatusFlow.stageIndex(it))
        }

        assertTrue(WalkInStatusFlow.showsStages(WalkInStatus.Waiting))
    }

    @Test
    fun `passed stages are marked, the rest are not`() {
        val status = WalkInStatus.Waiting

        assertTrue(WalkInStatusFlow.isStageDone(WalkInStatus.Pending, status))
        assertTrue(WalkInStatusFlow.isStageDone(WalkInStatus.Accepted, status))
        // Текущий этап — точка, а не галочка.
        assertFalse(WalkInStatusFlow.isStageDone(WalkInStatus.Waiting, status))
        assertFalse(WalkInStatusFlow.isStageDone(WalkInStatus.Completed, status))
        // Состояние вне цепочки не отмечает ни один этап.
        assertFalse(
            WalkInStatusFlow.isStageDone(WalkInStatus.Pending, WalkInStatus.Cancelled),
        )
    }

    @Test
    fun `queue numbers are shown only while they are fresh`() {
        val ticket = ticket(queuePosition = 3, estimatedWaitMinutes = 25)

        assertTrue(ticket.showsQueueInfo(NOW))
        assertTrue(ticket.showsQueueInfo(NOW + Duration.ofSeconds(119)))
        // Дальше число перестаёт быть правдой: очередь двигают чужие отмены, а
        // перечитать её нечем — ручки чтения талона у бэкенда нет.
        assertFalse(ticket.showsQueueInfo(NOW + Duration.ofMinutes(2)))
        assertFalse(ticket.showsQueueInfo(NOW + Duration.ofHours(1)))
    }

    @Test
    fun `a ticket without numbers has nothing to show`() {
        // У `PENDING` позиции ещё нет: мастер не подтвердил запись.
        assertFalse(ticket().showsQueueInfo(NOW))
    }

    @Test
    fun `clock going backwards does not revive stale numbers`() {
        // Время на устройстве могли перевести: «минус пять минут» — не свежесть.
        assertFalse(
            ticket(queuePosition = 3).showsQueueInfo(NOW - Duration.ofMinutes(5)),
        )
    }

    @Test
    fun `a ticket older than half a day is not active any more`() {
        val ticket = ticket(queuePosition = 1)

        assertFalse(ticket.isOutdated(NOW + Duration.ofHours(11)))
        assertTrue(ticket.isOutdated(NOW + Duration.ofHours(12)))
    }

    @Test
    fun `the name is required and the service is not`() {
        val errors = WalkInRequestValidator.validate(
            WalkInRequest(placeId = "p-1", userName = "  "),
        )

        assertEquals(listOf(WalkInRequestError.NameRequired), errors)
        assertTrue(
            WalkInRequestValidator.validate(
                WalkInRequest(placeId = "p-1", userName = "Jahongir"),
            ).isEmpty(),
        )
    }

    @Test
    fun `both length limits are reported at once`() {
        val errors = WalkInRequestValidator.validate(
            WalkInRequest(
                placeId = "p-1",
                userName = "a".repeat(WalkInRequest.MAX_NAME_LENGTH + 1),
                serviceName = "b".repeat(WalkInRequest.MAX_SERVICE_LENGTH + 1),
            ),
        )

        assertEquals(
            listOf(
                WalkInRequestError.NameTooLong(WalkInRequest.MAX_NAME_LENGTH),
                WalkInRequestError.ServiceTooLong(WalkInRequest.MAX_SERVICE_LENGTH),
            ),
            errors,
        )
    }

    @Test
    fun `trimming does not cut the length the person typed`() {
        val request = WalkInRequest(placeId = "p-1", userName = "  Jahongir  ", serviceName = " ")

        assertEquals("Jahongir", request.trimmed().userName)
        // Пробелы услугой не считаются: в тело запроса поле не попадёт вовсе.
        assertNull(request.serviceOrNull())
    }

    private fun ticket(
        status: WalkInStatus = WalkInStatus.Pending,
        queuePosition: Int? = null,
        estimatedWaitMinutes: Int? = null,
    ) = WalkInTicket(
        id = "t-1",
        placeId = "p-1",
        status = status,
        queuePosition = queuePosition,
        estimatedWaitMinutes = estimatedWaitMinutes,
        receivedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
    }
}
