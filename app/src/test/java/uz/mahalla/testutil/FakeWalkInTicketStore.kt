package uz.mahalla.testutil

import uz.mahalla.feature.queue.data.WalkInTicketStore
import uz.mahalla.feature.queue.domain.WalkInStatusFlow
import uz.mahalla.feature.queue.domain.WalkInTicket

/**
 * Взятые талоны в памяти (issue #96): репозиторий проверяется на MockWebServer,
 * но без DataStore.
 *
 * Правило «храним только живые» повторено намеренно: тест репозитория должен
 * видеть то же, что увидит экран, — иначе отмена «сохранялась» бы и в фейке.
 */
class FakeWalkInTicketStore : WalkInTicketStore {

    val saved = mutableListOf<WalkInTicket>()

    private val tickets = mutableMapOf<String, WalkInTicket>()

    fun put(ticket: WalkInTicket) {
        tickets[ticket.placeId] = ticket
    }

    override suspend fun active(placeId: String): WalkInTicket? = tickets[placeId]

    override suspend fun save(ticket: WalkInTicket) {
        saved += ticket
        if (WalkInStatusFlow.isActive(ticket.status)) {
            tickets[ticket.placeId] = ticket
        } else {
            tickets.remove(ticket.placeId)
        }
    }
}
