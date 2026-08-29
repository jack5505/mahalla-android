package uz.mahalla.feature.discovery.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.payload
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
 * Два разных эндпоинта под одним методом (issue #53): пустой запрос — это
 * «покажи, что рядом» (`places/nearby` с координатами и радиусом), непустой —
 * поиск по индексу (`search`). Своего `GET places` у бэкенда нет вовсе, и
 * ровно поэтому главная отвечала 403.
 *
 * Пагинации у обоих нет: сервер отдаёт всё найденное одним списком, поэтому
 * [PlacePage.hasMore] всегда `false`, а страницы старше нулевой не
 * запрашиваются.
 *
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
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
) : CatalogRepository {

    override suspend fun places(filters: DiscoveryFilters, page: Int): ApiResult<PlacePage> {
        // Сервер не пагинирует: догружать нечего, а сходить за той же первой
        // страницей значило бы дописать её в список второй раз.
        if (page > 0) return ApiResult.Success(PlacePage(emptyList(), page, hasMore = false))

        val location = location()
        val query = filters.query.trim()
        val response = apiCall {
            if (query.isEmpty()) {
                api.nearby(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    radiusMeters = filters.maxDistanceMeters ?: CatalogApi.DEFAULT_RADIUS_METERS,
                    category = filters.apiCategory(),
                ).payload().map { it.toDomain(location) }
            } else {
                api.search(query = query, category = filters.apiCategory())
                    .payload()
                    .map { it.toDomain(location) }
            }
        }

        return when (response) {
            is ApiResult.Success -> {
                if (filters.isUnfiltered) cache(response.data)
                ApiResult.Success(
                    PlacePage(
                        // Порядок задаём сами — он должен совпадать с офлайновым.
                        // А вот фильтровать ответ повторно нельзя: критерии у
                        // сервера шире (см. PlaceFilterEngine.applyRemote).
                        items = PlaceFilterEngine.applyRemote(response.data, filters),
                        page = 0,
                        hasMore = false,
                    ),
                )
            }

            is ApiResult.Failure -> cachedPage(filters, page, response.failure)
        }
    }

    override suspend fun placeDetails(placeId: String): ApiResult<PlaceDetails> {
        val place = apiCall { api.place(placeId).payload() }
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
        val location = location()
        cacheDetails(dto, location)
        // Отзывы — отдельный запрос: их отсутствие не должно ронять карточку,
        // ради которой человек сюда пришёл.
        val reviews = reviews(placeId).let { if (it is ApiResult.Success) it.data else emptyList() }
        return ApiResult.Success(dto.toDetails(reviews, location))
    }

    override suspend fun reviews(placeId: String, page: Int): ApiResult<List<Review>> =
        apiCall { api.reviews(placeId, page).payload().content.map(ReviewDto::toDomain) }

    /**
     * Координаты обязательны для `places/nearby` и нужны, чтобы посчитать
     * расстояние в ответе поиска. [RequestLocationProvider] отдаёт настоящую
     * позицию, а без разрешения — центр выбранного города: пустой экран из-за
     * отсутствия координат хуже, чем выдача по центру города.
     */
    private suspend fun location(): DeviceLocation = locationProvider.current()

    private suspend fun cachedPage(
        filters: DiscoveryFilters,
        page: Int,
        failure: ApiFailure,
    ): ApiResult<PlacePage> {
        // Ответ сервера доносится до экрана как есть: если кэш не спас, человек
        // должен увидеть, что именно сказал бэкенд (issue #34).
        if (page > 0) return ApiResult.Failure(failure)

        val cached = placeDao.nearest(CACHE_PAGE_SIZE).map { it.toDomain() }
        val filtered = PlaceFilterEngine.apply(cached, filters)
        if (filtered.isEmpty()) return ApiResult.Failure(failure)

        return ApiResult.Success(
            PlacePage(items = filtered, page = 0, hasMore = false, fromCache = true),
        )
    }

    private suspend fun cache(places: List<Place>) {
        if (places.isEmpty()) return
        val now = clock.instant().epochSecond
        placeDao.upsert(places.map { it.toEntity(now) })
        placeDao.deleteStale(now - CACHE_TTL_SECONDS)
    }

    /**
     * Карточка кэшируется целиком: открытая офлайн, она иначе показывала бы
     * одно название вместо описания и телефона.
     */
    private suspend fun cacheDetails(dto: PlaceDetailDto, location: DeviceLocation) {
        val now = clock.instant().epochSecond
        placeDao.upsert(
            listOf(
                dto.toDomain(location).toEntity(
                    updatedAtEpochSeconds = now,
                    description = dto.description,
                    phone = dto.phone,
                    website = dto.website,
                ),
            ),
        )
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
