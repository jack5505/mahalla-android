package uz.mahalla.data.network.analytics

import uz.mahalla.core.analytics.AnalyticsEvent
import uz.mahalla.core.analytics.AnalyticsRepository
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.prefs.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/** Реализация [AnalyticsRepository] на основном Retrofit (issue #169). */
@Singleton
class DefaultAnalyticsRepository @Inject constructor(
    private val api: AnalyticsApi,
    private val sessionStore: SessionStore,
) : AnalyticsRepository {

    override suspend fun track(event: AnalyticsEvent): ApiResult<Unit> {
        // Заведения нет — сервер ответит `400`: `placeId` обязателен. Это
        // ошибка вызывающего, и запрос её не исправит.
        if (event.placeId.isBlank()) {
            return ApiResult.Failure(ApiError.Business(BLANK_PLACE_ID))
        }
        // Ручка не анонимна (проба 2026-09-10: аноним с гео-заголовками
        // получает 401). Каталог же смотрят и до входа, поэтому без сессии
        // запрос не делается вовсе: он гарантированно окажется отказом, а
        // `TokenAuthenticator` на нём ещё и попробует refresh, которого нет.
        if (sessionStore.current() == null) {
            return ApiResult.Failure(ApiError.Business(NO_SESSION))
        }
        return apiCall {
            api.track(
                TrackEventRequest(
                    placeId = event.placeId,
                    eventType = event.type.serverName,
                    metadata = event.metadata.takeIf { it.isNotEmpty() },
                ),
            ).ensureSuccess()
        }
    }

    private companion object {
        /**
         * Машинные коды для отказов, которые придумал клиент, а не сервер: в
         * логе они отличимы и от настоящего `VALIDATION_ERROR`, и от
         * настоящего `401` после провалившегося refresh.
         */
        const val BLANK_PLACE_ID = "ANALYTICS_BLANK_PLACE_ID"
        const val NO_SESSION = "ANALYTICS_NO_SESSION"
    }
}
