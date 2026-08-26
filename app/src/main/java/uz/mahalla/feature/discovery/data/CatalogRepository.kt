package uz.mahalla.feature.discovery.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceFilterEngine
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Страница выдачи. [fromCache] — данные подняты из Room после сетевой ошибки;
 * экран обязан это показать, иначе устаревший список выглядит как свежий.
 */
data class PlacePage(
    val items: List<Place>,
    val page: Int,
    val hasMore: Boolean,
    val fromCache: Boolean = false,
)

/**
 * Каталог мест (эпик 4): сеть с фоллбэком на кэш Room.
 *
 * Интерфейс нужен ради тестов ViewModel — фейк подставляется без MockWebServer
 * и без базы.
 */
interface CatalogRepository {

    suspend fun places(filters: DiscoveryFilters, page: Int = 0): ApiResult<PlacePage>

    suspend fun placeDetails(placeId: String): ApiResult<PlaceDetails>

    suspend fun reviews(placeId: String, page: Int = 0): ApiResult<List<Review>>
}

/**
 * Правила фоллбэка:
 *
 * - первая страница при сетевой ошибке отдаётся из кэша, отфильтрованная
 *   [PlaceFilterEngine] целиком: кроме этих правил у кэша ничего нет;
 * - ответ сервера повторно не фильтруется — только сортируется
 *   ([PlaceFilterEngine.applyRemote]);
 * - карточка поднимается из кэша только когда место просто не доехало
 *   (сеть, таймаут, 5xx). На [ApiError.NotFound] кэш не спасает: место
 *   удалили, и показать его копию значит соврать;
 * - вторая и дальше — не отдаются: дорисовать «хвост» списка из кэша значит
 *   показать вперемешку свежие и старые данные, а этого пользователь не
 *   различит;
 * - пустой кэш — обычная ошибка, экран покажет retry;
 * - из кэша сеть не подменяется молча: [PlacePage.fromCache] доезжает до UI.
 *
 * Кэш перезаписывается только на успешной первой странице без единого
 * ограничения ([DiscoveryFilters.isUnfiltered]) — иначе он превратился бы в
 * срез последнего поиска, и офлайн-главная показывала бы то, что человек искал
 * вчера, вместо всего, что рядом.
 */
@Singleton
class DefaultCatalogRepository @Inject constructor(
    private val api: CatalogApi,
    private val placeDao: PlaceDao,
    private val clock: Clock,
) : CatalogRepository {

    override suspend fun places(filters: DiscoveryFilters, page: Int): ApiResult<PlacePage> {
        val response = apiCall {
            api.places(
                category = filters.apiCategory(),
                query = filters.query.trim().takeIf(String::isNotEmpty),
                openNow = true.takeIf { filters.openNowOnly },
                maxDistanceMeters = filters.maxDistanceMeters,
                minRating = filters.minRating,
                sort = filters.sort.apiValue,
                page = page,
            )
        }

        return when (response) {
            is ApiResult.Success -> {
                val dto = response.data
                if (page == 0 && filters.isUnfiltered) cache(dto.items)
                ApiResult.Success(
                    PlacePage(
                        // Порядок задаём сами — он должен совпадать с офлайновым.
                        // А вот фильтровать ответ повторно нельзя: критерии у
                        // сервера шире (см. PlaceFilterEngine.applyRemote).
                        items = PlaceFilterEngine.applyRemote(
                            dto.items.map(PlaceDto::toDomain),
                            filters,
                        ),
                        page = dto.page,
                        hasMore = dto.page < dto.totalPages - 1,
                    ),
                )
            }

            is ApiResult.Failure -> cachedPage(filters, page, response.error)
        }
    }

    override suspend fun placeDetails(placeId: String): ApiResult<PlaceDetails> {
        val place = apiCall { api.place(placeId) }
        if (place is ApiResult.Failure) {
            // Место удалили или скрыли — своя копия из Room тут не помощь, а
            // выдумка: человек пойдёт по адресу, которого больше нет. Заодно
            // чистим кэш, чтобы оно не всплыло в офлайн-выдаче.
            if (place.error in GONE_ERRORS) {
                placeDao.delete(placeId)
                return place
            }
            val cached = placeDao.byId(placeId) ?: return place
            return ApiResult.Success(cached.toCachedDetails())
        }

        val dto = (place as ApiResult.Success).data
        cache(listOf(dto))
        // Отзывы — отдельный запрос: их отсутствие не должно ронять карточку,
        // ради которой человек сюда пришёл.
        val reviews = reviews(placeId).let { if (it is ApiResult.Success) it.data else emptyList() }
        return ApiResult.Success(dto.toDetails(reviews))
    }

    override suspend fun reviews(placeId: String, page: Int): ApiResult<List<Review>> =
        apiCall { api.reviews(placeId, page).items.map(ReviewDto::toDomain) }

    private suspend fun cachedPage(
        filters: DiscoveryFilters,
        page: Int,
        error: ApiError,
    ): ApiResult<PlacePage> {
        if (page > 0) return ApiResult.Failure(error)

        val cached = placeDao.nearest(CACHE_PAGE_SIZE).map { it.toDomain() }
        val filtered = PlaceFilterEngine.apply(cached, filters)
        if (filtered.isEmpty()) return ApiResult.Failure(error)

        return ApiResult.Success(
            PlacePage(items = filtered, page = 0, hasMore = false, fromCache = true),
        )
    }

    private suspend fun cache(places: List<PlaceDto>) {
        if (places.isEmpty()) return
        val now = clock.instant().epochSecond
        placeDao.upsert(places.map { it.toEntity(now) })
        placeDao.deleteStale(now - CACHE_TTL_SECONDS)
    }

    private companion object {
        /** Ответы, после которых кэшу верить нельзя: места больше нет. */
        val GONE_ERRORS = setOf(ApiError.NotFound, ApiError.Forbidden)

        /** Сколько мест держим в офлайн-выдаче — один экран прокрутки. */
        const val CACHE_PAGE_SIZE = 50

        /** Неделю назад расстояния и часы работы уже ничего не значат. */
        const val CACHE_TTL_SECONDS = 7L * 24 * 60 * 60
    }
}
