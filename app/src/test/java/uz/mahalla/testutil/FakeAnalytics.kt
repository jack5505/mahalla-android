package uz.mahalla.testutil

import uz.mahalla.core.analytics.AnalyticsEvent
import uz.mahalla.core.analytics.AnalyticsRepository
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.result.ApiResult
import java.util.Collections

/**
 * Запоминает отправленные события (issue #169): экранные тесты проверяют, что
 * событие ушло один раз, с тем видом и тем заведением.
 */
class FakeAnalyticsTracker : AnalyticsTracker {

    val events = mutableListOf<AnalyticsEvent>()

    override fun track(event: AnalyticsEvent) {
        events += event
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

    /**
     * Вызывается сразу после записи события — тесту на настоящей области
     * (`Dispatchers.IO`) нужен сигнал о доставке, а не опрос [events] по
     * стенным часам (issue #228, п. 3).
     */
    var onTrack: (() -> Unit)? = null

    override suspend fun track(event: AnalyticsEvent): ApiResult<Unit> {
        events += event
        onTrack?.invoke()
        crash?.let { throw it }
        return result
    }
}
