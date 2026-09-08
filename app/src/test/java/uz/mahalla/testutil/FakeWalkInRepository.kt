package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.queue.data.WalkInRepository
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import java.time.Instant

/** Очередь в памяти (issue #96): экран проверяется без MockWebServer. */
class FakeWalkInRepository : WalkInRepository {

    /** Что вернёт `take`; по умолчанию — талон в ожидании подтверждения. */
    var takeResult: ApiResult<WalkInTicket> = ApiResult.Success(walkInTicket())

    /** Что вернёт `cancel`; `null` — отменённый вариант переданного талона. */
    var cancelResult: ApiResult<WalkInTicket>? = null

    /** Живой талон, который экран найдёт при открытии. */
    var active: WalkInTicket? = null

    val taken = mutableListOf<Pair<WalkInRequest, String>>()

    val cancelled = mutableListOf<WalkInTicket>()

    override suspend fun take(
        request: WalkInRequest,
        placeName: String,
    ): ApiResult<WalkInTicket> {
        taken += request to placeName
        return takeResult
    }

    override suspend fun cancel(ticket: WalkInTicket): ApiResult<WalkInTicket> {
        cancelled += ticket
        return cancelResult
            ?: ApiResult.Success(ticket.copy(status = WalkInStatus.Cancelled))
    }

    override suspend fun activeTicket(placeId: String): WalkInTicket? =
        active?.takeIf { it.placeId == placeId }
}

/** Талон для тестов: значения по умолчанию — как у только что взятого. */
fun walkInTicket(
    id: String = "t-1",
    placeId: String = "p-1",
    placeName: String = "Barber House",
    status: WalkInStatus = WalkInStatus.Pending,
    queuePosition: Int? = null,
    estimatedWaitMinutes: Int? = null,
    receivedAt: Instant = Instant.parse("2026-09-04T09:00:00Z"),
): WalkInTicket = WalkInTicket(
    id = id,
    placeId = placeId,
    placeName = placeName,
    userName = "Jahongir",
    status = status,
    queuePosition = queuePosition,
    estimatedWaitMinutes = estimatedWaitMinutes,
    receivedAt = receivedAt,
)
