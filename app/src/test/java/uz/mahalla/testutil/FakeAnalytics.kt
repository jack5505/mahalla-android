package uz.mahalla.testutil

import uz.mahalla.core.analytics.AnalyticsEvent
import uz.mahalla.core.analytics.AnalyticsEventQueue
import uz.mahalla.core.analytics.AnalyticsQueuedEvent
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.analytics.AnalyticsRepository
import java.util.Collections

/**
 * Запоминает отправленные события (issue #169, #226): экранные тесты
 * проверяют, что событие ушло один раз, с тем видом (или именем) и тем
 * заведением.
 */
class FakeAnalyticsTracker : AnalyticsTracker {

    val events = mutableListOf<AnalyticsEvent>()
    val queuedEvents = mutableListOf<AnalyticsQueuedEvent>()

    override fun track(event: AnalyticsEvent) {
        events += event
    }

    override fun track(event: AnalyticsQueuedEvent) {
        queuedEvents += event
    }
}

/** Очередь аналитики в памяти: для тестов трекера и самой очереди. */
class FakeAnalyticsEventQueue : AnalyticsEventQueue {

    val events: MutableList<AnalyticsQueuedEvent> = Collections.synchronizedList(mutableListOf())

    var crash: Throwable? = null

    override suspend fun enqueue(event: AnalyticsQueuedEvent) {
        events += event
        crash?.let { throw it }
    }
}

/** Репозиторий аналитики в памяти: для тестов самого трекера. */
class FakeAnalyticsRepository : AnalyticsRepository {

    /**
     * Список синхронизированный: трекер из графа шлёт на `Dispatchers.IO`,
     * то есть запись идёт из чужого потока, а читает её тест из своего.
     */
    val events: MutableList<AnalyticsEvent> = Collections.synchronizedList(mutableListOf())

    var result: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Что бросить вместо ответа: трекер обязан выжить и после исключения. */
    var crash: Throwable? = null

    override suspend fun track(event: AnalyticsEvent): ApiResult<Unit> {
        events += event
        crash?.let { throw it }
        return result
    }
}
