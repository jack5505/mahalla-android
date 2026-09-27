package uz.mahalla.core.analytics

import uz.mahalla.core.result.ApiResult

/**
 * Отправка событий продуктовой аналитики (issue #169).
 *
 * Интерфейс лежит в `core`, а не рядом с реализацией в `data/network/analytics`
 * (issue #228, п. 6): `AnalyticsTracker` — код `core` — раньше зависел от
 * `data` напрямую, хотя ему нужен только контракт отправки, а не то, что
 * реализация ходит через Retrofit.
 *
 * Метод suspend и возвращает [ApiResult], как любой другой репозиторий, — но
 * вызывать его из экрана напрямую не надо: для этого есть
 * `AnalyticsTracker`, который не держит корутину экрана.
 */
interface AnalyticsRepository {

    /**
     * @return [ApiResult.Success] — сервер принял событие. Отказ вызывающий
     * пишет в лог: показывать его человеку нечего, он про аналитику не просил.
     */
    suspend fun track(event: AnalyticsEvent): ApiResult<Unit>
}
