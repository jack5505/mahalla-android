package uz.mahalla.core.analytics

/**
 * Очередь для [AnalyticsQueuedEvent] (issue #226).
 *
 * В отличие от [AnalyticsTracker.track] с [AnalyticsEvent] (один запрос,
 * событие теряется без сети — `docs/adr/0006`), здесь событие сперва
 * записывается на диск и только потом уходит батчем: `analytics/events`
 * умеет и то, из-за чего `track` не мог себе этого позволить, — поле времени
 * события и батч-ручку.
 *
 * [enqueue] — suspend, но вызывается только изнутри собственной области
 * `AnalyticsTracker` («выстрелил и забыл» — уровнем выше, а не здесь): так
 * запись на диск и отправка делят с [AnalyticsEvent] одну и ту же защиту от
 * исключений вместо того, чтобы заводить её дважды.
 */
interface AnalyticsEventQueue {

    suspend fun enqueue(event: AnalyticsQueuedEvent)
}
