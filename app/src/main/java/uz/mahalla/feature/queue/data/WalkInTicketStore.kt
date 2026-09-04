package uz.mahalla.feature.queue.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.PreferenceKeys
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInStatusFlow
import uz.mahalla.feature.queue.domain.WalkInTicket
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Взятый талон — локально (issue #96).
 *
 * Хранилище нужно потому, что **прочитать талон у бэкенда нечем**: ручки
 * `walkin/my` / `walkin/{id}` нет, состояние приезжает только в ответе на
 * запись. Без него человек, свернувший приложение, терял бы и номер в
 * очереди, и возможность отменить запись.
 *
 * Это **последнее известное** состояние, а не текущее. Экран обязан
 * подписывать его временем (`WalkInTicket.receivedAt`), а позицию в очереди
 * показывать только пока она свежая — правило живёт в
 * [WalkInTicket.showsQueueInfo].
 *
 * Хранятся только живые талоны: финальные ([WalkInStatusFlow.isFinal]) и
 * просроченные ([WalkInTicket.isOutdated]) выбрасываются при чтении и записи —
 * иначе вчерашний талон запирал бы запись на сегодня.
 */
interface WalkInTicketStore {

    /** Живой талон этого заведения или `null`. */
    suspend fun active(placeId: String): WalkInTicket?

    /**
     * Запомнить состояние талона. Отдельного «удалить» нет намеренно:
     * отменённый и завершённый талон хранилище выбрасывает само, а значит
     * отмена — это та же запись нового состояния.
     */
    suspend fun save(ticket: WalkInTicket)
}

@Singleton
class DataStoreWalkInTicketStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : WalkInTicketStore {

    override suspend fun active(placeId: String): WalkInTicket? =
        runCatchingCancellable {
            val now = clock.instant()
            val preferences = dataStore.data.first()
            decode(preferences[PreferenceKeys.WalkInTickets])
                .firstOrNull { it.placeId == placeId && it.isAlive(now) }
        }
            // Талон — удобство, а не условие работы экрана: недоступные
            // настройки означают «активного талона не знаем», и человек просто
            // увидит форму записи.
            .reportSwallowed("queue.readTicket")
            .getOrNull()

    /**
     * Записывается только живой талон, и заодно чистятся чужие мёртвые: один и
     * тот же человек ходит в разные заведения, а разбирать мусор потом будет
     * нечем — ответа сервера по этим талонам больше не будет.
     */
    override suspend fun save(ticket: WalkInTicket) {
        update { tickets ->
            tickets.filterNot { it.id == ticket.id || it.placeId == ticket.placeId } +
                listOfNotNull(ticket.takeIf { it.isAlive(clock.instant()) })
        }
    }

    private suspend fun update(transform: (List<WalkInTicket>) -> List<WalkInTicket>) {
        runCatchingCancellable {
            val now = clock.instant()
            dataStore.edit { preferences ->
                val current = decode(preferences[PreferenceKeys.WalkInTickets])
                val next = transform(current).filter { it.isAlive(now) }.takeLast(MAX_TICKETS)
                if (next.isEmpty()) {
                    preferences.remove(PreferenceKeys.WalkInTickets)
                } else {
                    preferences[PreferenceKeys.WalkInTickets] = encode(next)
                }
            }
        }
            // Запись талона не должна ронять саму запись в очередь: она уже
            // состоялась на сервере, и экран показывает её из состояния.
            .reportSwallowed("queue.saveTicket")
    }

    private fun WalkInTicket.isAlive(now: Instant): Boolean =
        WalkInStatusFlow.isActive(status) && !isOutdated(now)

    private fun decode(raw: String?): List<WalkInTicket> {
        val stored = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        // Формат мог измениться в прошлой версии приложения: испорченная
        // строка — это «талонов нет», а не падение экрана.
        return runCatchingCancellable { json.decodeFromString(storedTickets, stored) }
            .getOrNull()
            ?.mapNotNull(StoredTicket::toDomain)
            .orEmpty()
    }

    private fun encode(tickets: List<WalkInTicket>): String =
        json.encodeToString(storedTickets, tickets.map(WalkInTicket::toStored))

    private companion object {
        /**
         * Больше и не нужно: талон берут в заведении, где человек сейчас
         * находится, а список ради истории здесь не ведётся.
         */
        const val MAX_TICKETS = 5

        val json = Json { ignoreUnknownKeys = true }

        val storedTickets = ListSerializer(StoredTicket.serializer())
    }
}

/**
 * Талон в хранилище. Отдельно от домена намеренно: имена и типы полей
 * `WalkInTicket` меняются вместе с контрактом бэкенда, а формат на диске
 * обязан оставаться читаемым для установленных версий приложения.
 */
@Serializable
private data class StoredTicket(
    @SerialName("id") val id: String,
    @SerialName("placeId") val placeId: String,
    @SerialName("placeName") val placeName: String = "",
    @SerialName("userName") val userName: String = "",
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("status") val status: String,
    @SerialName("queuePosition") val queuePosition: Int? = null,
    @SerialName("waitMinutes") val waitMinutes: Int? = null,
    @SerialName("counterMinuteOfDay") val counterMinuteOfDay: Int? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("createdAtEpochSeconds") val createdAtEpochSeconds: Long? = null,
    @SerialName("receivedAtEpochSeconds") val receivedAtEpochSeconds: Long,
)

private fun WalkInTicket.toStored(): StoredTicket = StoredTicket(
    id = id,
    placeId = placeId,
    placeName = placeName,
    userName = userName,
    serviceName = serviceName,
    status = status.apiValue,
    queuePosition = queuePosition,
    waitMinutes = estimatedWaitMinutes,
    counterMinuteOfDay = counterTime?.let { it.hour * MINUTES_IN_HOUR + it.minute },
    note = note,
    createdAtEpochSeconds = createdAt?.epochSecond,
    receivedAtEpochSeconds = receivedAt.epochSecond,
)

private fun StoredTicket.toDomain(): WalkInTicket? {
    if (id.isBlank() || placeId.isBlank()) return null
    return WalkInTicket(
        id = id,
        placeId = placeId,
        placeName = placeName,
        userName = userName,
        serviceName = serviceName,
        status = WalkInStatus.fromApi(status),
        queuePosition = queuePosition,
        estimatedWaitMinutes = waitMinutes,
        counterTime = counterMinuteOfDay
            ?.takeIf { it in 0 until MINUTES_IN_DAY }
            ?.let { LocalTime.of(it / MINUTES_IN_HOUR, it % MINUTES_IN_HOUR) },
        note = note,
        createdAt = createdAtEpochSeconds?.let(Instant::ofEpochSecond),
        receivedAt = Instant.ofEpochSecond(receivedAtEpochSeconds),
    )
}

private const val MINUTES_IN_HOUR = 60
private const val MINUTES_IN_DAY = 24 * MINUTES_IN_HOUR
