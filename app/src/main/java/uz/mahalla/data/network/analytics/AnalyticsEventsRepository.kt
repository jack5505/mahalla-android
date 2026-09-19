package uz.mahalla.data.network.analytics

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправка пачки [AnalyticsEventItemRequest] на `POST analytics/events`
 * (issue #226). Само устройство очереди (диск, ретраи, обрезка по возрасту) —
 * в `DefaultAnalyticsEventQueue`; здесь только запрос.
 */
interface AnalyticsEventsRepository {

    /** @return [ApiResult.Success] несёт `accepted` — см. KDoc [AnalyticsApi]. */
    suspend fun send(deviceId: String, events: List<AnalyticsEventItemRequest>): ApiResult<Int>
}

@Singleton
class DefaultAnalyticsEventsRepository @Inject constructor(
    private val api: AnalyticsApi,
) : AnalyticsEventsRepository {

    override suspend fun send(deviceId: String, events: List<AnalyticsEventItemRequest>): ApiResult<Int> =
        apiCall {
            api.events(AnalyticsEventsRequest(deviceId = deviceId, events = events)).payload().accepted
        }
}
