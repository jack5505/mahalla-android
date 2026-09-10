package uz.mahalla.feature.activity.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uz.mahalla.core.paging.hasMorePages
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.booking.data.BookingApi
import uz.mahalla.feature.cinema.data.CinemaApi
import uz.mahalla.feature.cinema.data.CinemaTicketDto
import uz.mahalla.feature.fashion.data.FashionApi
import uz.mahalla.feature.food.data.OrderViewDto
import uz.mahalla.feature.gaming.data.GamingApi
import uz.mahalla.feature.gaming.data.GamingBookingDto
import uz.mahalla.feature.hospital.data.HospitalApi
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

/**
 * Своего `ActivityApi` у фичи нет (issue #142): все пять ручек уже объявлены
 * в вертикалях, которым принадлежат, — и объявлены там раньше. Дубль успел
 * разъехаться с оригиналом ещё до слияния этой ветки, поэтому источники
 * читаются существующими API как есть.
 *
 * Заказы идут через [FashionApi.myOrders] — общий `GET orders`, но **без**
 * `vertical`: фильтра нет, и приезжают заказы всех вертикалей сразу. Записи к
 * врачу — [HospitalApi], к мастеру — [BookingApi]; схема ответа у них одна на
 * двоих (`AppointmentResponse`), различает их только источник.
 */
@Singleton
class DefaultActivityRepository @Inject constructor(
    private val fashionApi: FashionApi,
    private val gamingApi: GamingApi,
    private val bookingApi: BookingApi,
    private val hospitalApi: HospitalApi,
    private val cinemaApi: CinemaApi,
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
            // `vertical = null` — заказы всех вертикалей сразу, одним
            // запросом вместо пяти. Статус тоже не передаётся: фильтр
            // «активные / история» работает на клиенте по уже приехавшему
            // списку, потому что «активное» — это набор статусов, а параметр
            // `status` принимает ровно один.
            val dto = fashionApi.myOrders(vertical = null, page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull(OrderViewDto::toActivity),
                hasMore = hasMorePages(page, dto.totalPages, dto.last),
            )
        }

        ActivitySource.GamingBookings -> apiCall {
            val dto = gamingApi.myBookings(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull(GamingBookingDto::toActivity),
                hasMore = hasMorePages(page, dto.totalPages, dto.last),
            )
        }

        ActivitySource.MasterAppointments -> apiCall {
            val dto = bookingApi.myAppointments(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull { it.toActivity(ActivitySource.MasterAppointments) },
                hasMore = hasMorePages(page, dto.totalPages, dto.last),
            )
        }

        ActivitySource.DoctorAppointments -> apiCall {
            val dto = hospitalApi.myAppointments(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull { it.toActivity(ActivitySource.DoctorAppointments) },
                hasMore = hasMorePages(page, dto.totalPages, dto.last),
            )
        }

        ActivitySource.CinemaTickets -> apiCall {
            val dto = cinemaApi.myTickets(page = page, size = size).payload()
            SourcePage(
                items = dto.content.mapNotNull(CinemaTicketDto::toActivity),
                hasMore = hasMorePages(page, dto.totalPages, dto.last),
            )
        }
    }

    private data class SourcePage(val items: List<Activity>, val hasMore: Boolean)
}
