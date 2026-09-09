package uz.mahalla.feature.activity.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.food.data.OrderViewDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Мои активности» (issue #73, задача T7): один список из пяти источников.
 *
 * Кэша нет намеренно: статус заказа и состояние брони меняются на сервере, и
 * устаревшая запись из Room — это «ваш заказ готовится» у заказа, который
 * привезли час назад. Честная ошибка полезнее.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface ActivityRepository {

    /**
     * Загрузить по одной странице у каждого перечисленного источника.
     *
     * @param pages какой источник и какую его страницу спрашивать.
     * Источника нет в карте — запроса к нему не будет вовсе: страницы у пяти
     * ручек кончаются в разное время, и просить у исчерпанного источника
     * следующую страницу значит получать один и тот же хвост заново.
     *
     * Возвращается [ActivityFeed], а не `ApiResult`: пять независимых ручек не
     * сводятся к одному «получилось / не получилось» — см. KDoc `ActivityFeed`.
     */
    suspend fun feed(
        pages: Map<ActivitySource, Int> = ActivityFeed.FIRST_PAGES,
        size: Int = PAGE_SIZE,
    ): ActivityFeed

    companion object {
        /** Столько же по умолчанию берут и сами ручки бэкенда. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultActivityRepository @Inject constructor(
    private val api: ActivityApi,
) : ActivityRepository {

    /**
     * Источники опрашиваются **параллельно**: последовательно пять запросов
     * заняли бы пять сетевых задержек подряд, а зависят они друг от друга
     * никак. Отказ одного при этом не отменяет остальных — `apiCall` не
     * выпускает исключений, поэтому `async` здесь не роняет `coroutineScope`.
     */
    override suspend fun feed(pages: Map<ActivitySource, Int>, size: Int): ActivityFeed =
        coroutineScope {
            val requested = pages.keys.toSet()
            val pageSize = size.coerceAtLeast(1)
            val loaded = requested
                .map { source ->
                    val page = pages.getValue(source).coerceAtLeast(0)
                    async { source to load(source, page, pageSize) }
                }
                .map { it.await() }

            val items = mutableListOf<Activity>()
            val failures = mutableMapOf<ActivitySource, ApiFailure>()
            val nextPages = mutableMapOf<ActivitySource, Int>()
            loaded.forEach { (source, result) ->
                when (result) {
                    is ApiResult.Failure -> failures[source] = result.failure
                    is ApiResult.Success -> {
                        items += result.data.items
                        if (result.data.hasMore) {
                            nextPages[source] = pages.getValue(source).coerceAtLeast(0) + 1
                        }
                    }
                }
            }

            ActivityFeed(
                items = items,
                failures = failures,
                nextPages = nextPages,
                requested = requested,
            )
        }

    private suspend fun load(
        source: ActivitySource,
        page: Int,
        size: Int,
    ): ApiResult<SourcePage> = when (source) {
        ActivitySource.Orders -> apiCall {
            val dto = api.orders(page = page, size = size).payload()
            SourcePage(dto.content.mapNotNull(OrderViewDto::toActivity), dto.hasMore(page))
        }

        ActivitySource.GamingBookings -> apiCall {
            val dto = api.gamingBookings(page = page, size = size).payload()
            SourcePage(dto.content.mapNotNull(GamingBookingDto::toActivity), dto.hasMore(page))
        }

        ActivitySource.MasterAppointments -> apiCall {
            val dto = api.masterAppointments(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull { it.toActivity(ActivitySource.MasterAppointments) },
                hasMore = dto.hasMore(page),
            )
        }

        ActivitySource.DoctorAppointments -> apiCall {
            val dto = api.doctorAppointments(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull { it.toActivity(ActivitySource.DoctorAppointments) },
                hasMore = dto.hasMore(page),
            )
        }

        ActivitySource.CinemaTickets -> apiCall {
            val dto = api.cinemaTickets(page = page, size = size).payload()
            SourcePage(dto.content.mapNotNull(CinemaTicketDto::toActivity), dto.hasMore(page))
        }
    }

    private data class SourcePage(val items: List<Activity>, val hasMore: Boolean)
}
