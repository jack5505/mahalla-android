package uz.mahalla.data.network.analytics

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uz.mahalla.core.analytics.AnalyticsEventQueue
import uz.mahalla.core.analytics.AnalyticsQueuedEvent
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.db.dao.AnalyticsEventDao
import uz.mahalla.data.db.entity.AnalyticsEventEntity
import uz.mahalla.data.device.DeviceIdStore
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Диск + отправка для `analytics/events` (issue #226).
 *
 * Алгоритм [flush] намеренно простой — нет ни `WorkManager`, ни периодического
 * таймера: каждый [enqueue] сам пытается разгрести всю накопленную очередь, а
 * не только своё событие. Этого достаточно, чтобы застрявшее после простоя
 * без сети досылалось при следующем же действии человека в приложении
 * (открыл любой экран без заведения → сработал `screen_view` → заодно ушло и
 * то, что скопилось раньше), и не требует фоновой работы ради ручки, которая
 * не обязана доставить событие сию секунду.
 */
@Singleton
class DefaultAnalyticsEventQueue @Inject constructor(
    private val dao: AnalyticsEventDao,
    private val repository: AnalyticsEventsRepository,
    private val deviceIdStore: DeviceIdStore,
    private val clock: Clock,
    private val json: Json,
) : AnalyticsEventQueue {

    /**
     * Один флаш одновременно: параллельные [enqueue] иначе отправили бы одну
     * и ту же старую строку в двух пачках сразу — на сервере без дедупликации
     * (`jack5505/mahalla#267`) это задвоенное событие, а не ошибка.
     */
    private val flushMutex = Mutex()

    override suspend fun enqueue(event: AnalyticsQueuedEvent) {
        val now = clock.instant()
        dao.insert(
            AnalyticsEventEntity(
                name = event.name,
                occurredAt = now.toString(),
                placeId = event.placeId,
                metadataJson = event.metadata.takeIf { it.isNotEmpty() }?.let(json::encodeToString),
                enqueuedAtEpochSecond = now.epochSecond,
            ),
        )
        flush()
    }

    private suspend fun flush() {
        // Кто-то уже разгребает очередь — второй проход по тем же строкам
        // не ускорит доставку, только рискует задвоить пачку.
        if (!flushMutex.tryLock()) return
        try {
            // Событие старше 30 суток сервер не примет в любом случае
            // (`VALIDATION_ERROR`) — обрезаем до отправки, а не после отказа.
            dao.deleteOlderThan(clock.instant().minus(MAX_AGE).epochSecond)
            while (true) {
                val batch = dao.oldest(BATCH_LIMIT)
                if (batch.isEmpty()) return
                val deviceId = deviceIdStore.deviceId()
                val result = repository.send(deviceId, batch.map { it.toRequest() })
                val ids = batch.map { it.id }
                when (result) {
                    // `accepted` может быть меньше присланного — сервер уже
                    // отбросил негодные события поштучно, переотправка пачки
                    // их не исправит (issue #226, KDoc `AnalyticsApi`).
                    is ApiResult.Success -> dao.deleteByIds(ids)
                    is ApiResult.Failure ->
                        if (result.error.isRetryable()) {
                            // Оставляем в очереди: место в п.1 внизу цикла.
                            return
                        } else {
                            // Отклонена целиком и не сетевая причина — то же
                            // тело даст тот же отказ и на следующей попытке.
                            dao.deleteByIds(ids)
                        }
                }
                if (batch.size < BATCH_LIMIT) return
            }
        } finally {
            flushMutex.unlock()
        }
    }

    private fun AnalyticsEventEntity.toRequest(): AnalyticsEventItemRequest =
        AnalyticsEventItemRequest(
            name = name,
            occurredAt = occurredAt,
            placeId = placeId,
            metadata = metadataJson?.let { json.decodeFromString<Map<String, String>>(it) },
        )

    /**
     * Стоит ли оставить пачку в очереди и попробовать позже.
     *
     * `RATE_LIMITED` (429) и 5xx — сервер попросил подождать или сам не
     * ответил, `NoConnection`/`Timeout` — до сервера не достучались вовсе,
     * `Unauthorized` — протухший `Authorization` (событие уйдёт и без него,
     * `deviceId` для этой ручки достаточно, см. KDoc `AnalyticsApi`).
     * `VALIDATION_ERROR` (обычный `Http(400)`) — структурная причина
     * (`name`, размер пачки), тот же запрос повторно даст тот же отказ, и
     * копить поломанную пачку в очереди бессмысленно.
     */
    private fun ApiError.isRetryable(): Boolean = when (this) {
        ApiError.NoConnection, ApiError.Timeout, ApiError.Unauthorized, ApiError.Serialization -> true
        is ApiError.Unexpected -> true
        is ApiError.Http -> code == RATE_LIMITED_CODE || code >= SERVER_ERROR_CODE
        ApiError.Forbidden, ApiError.NotFound -> false
        is ApiError.Business -> false
    }

    private companion object {
        const val BATCH_LIMIT = 100
        const val RATE_LIMITED_CODE = 429
        const val SERVER_ERROR_CODE = 500
        val MAX_AGE: Duration = Duration.ofDays(30)
    }
}
