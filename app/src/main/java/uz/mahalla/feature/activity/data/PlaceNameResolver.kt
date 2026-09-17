package uz.mahalla.feature.activity.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import uz.mahalla.feature.discovery.data.CatalogApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Резолвер `placeName`/`logoUrl` по `placeId` (issue #182, снимает
 * клиентскую часть #150).
 *
 * `OrderView`, `GamingBooking` и `AppointmentResponse` отдают только
 * `placeId` — названия заведения в них нет. `GET places?ids=` резолвит его
 * одним запросом на всю страницу «моих активностей», а не по одному вызову
 * на активность.
 *
 * Кэш — на сессию: `ConcurrentHashMap` живёт вместе с синглтоном, повторно
 * те же id не переспрашиваются. В Room его класть незачем — устаревшее имя
 * заведения безвредно, а вот переживший перезапуск battery drain от лишних
 * походов в сеть того не стоит.
 */
interface PlaceNameResolver {

    /**
     * Резолвит все переданные id разом.
     *
     * Отказ пачки не бросает исключение и не роняет остальные пачки — id,
     * которые не резолвились, просто отсутствуют в результате, и активность
     * остаётся без названия, как до этой задачи.
     */
    suspend fun resolve(placeIds: Collection<String>): Map<String, ResolvedPlace>

    data class ResolvedPlace(val name: String, val logoUrl: String?)
}

@Singleton
class DefaultPlaceNameResolver @Inject constructor(
    private val catalogApi: CatalogApi,
) : PlaceNameResolver {

    private val cache = ConcurrentHashMap<String, PlaceNameResolver.ResolvedPlace>()

    override suspend fun resolve(
        placeIds: Collection<String>,
    ): Map<String, PlaceNameResolver.ResolvedPlace> {
        val distinct = placeIds.filter(String::isNotBlank).distinct()
        val unknown = distinct.filterNot(cache::containsKey)

        // Пачки по BATCH_SIZE: лимита на число `ids` в схеме нет, а длинная
        // строка запроса на сотнях id рискует упереться в ограничение
        // сервера. Пустой список — пустой `chunked`, запроса не будет вовсе.
        unknown.chunked(BATCH_SIZE).forEach { batch ->
            val result = apiCall { catalogApi.places(batch).payload() }
            if (result is ApiResult.Success) {
                result.data.forEach { place ->
                    cache[place.id] = PlaceNameResolver.ResolvedPlace(place.name, place.logoUrl)
                }
            }
        }

        return distinct.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}
