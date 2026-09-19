package uz.mahalla.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Строка очереди `analytics/events` (issue #226, v4 → v5).
 *
 * [occurredAt] — ISO-8601 UTC момента, когда событие произошло на устройстве
 * (записывается при постановке в очередь, а не при отправке — иначе досылка
 * после простоя сдвинула бы воронку так же, как боялся `docs/adr/0006`).
 * [enqueuedAtEpochSecond] — отдельное поле для обрезки очереди по возрасту
 * (сервер не примет событие старше 30 суток): `occurredAt` для этого не
 * годится, это строка, а не индексируемое число.
 */
@Entity(tableName = "analytics_events")
data class AnalyticsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val occurredAt: String,
    val placeId: String? = null,
    /** `AnalyticsQueuedEvent.metadata`, сериализованная в JSON; `null` — пустая карта. */
    val metadataJson: String? = null,
    val enqueuedAtEpochSecond: Long,
)
