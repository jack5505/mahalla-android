package uz.mahalla.testutil

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.data.db.entity.PlaceEntity
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.PlacePage
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Место с разумными значениями по умолчанию — тесты задают только важное. */
fun place(
    id: String,
    name: String = "Place $id",
    category: PlaceCategory = PlaceCategory.Food,
    rating: Double = 4.5,
    reviewCount: Int = 20,
    distanceMeters: Int = 500,
    isOpenNow: Boolean = true,
    address: String? = null,
    point: GeoPoint? = null,
    isRecommended: Boolean = false,
): Place = Place(
    id = id,
    name = name,
    category = category,
    rating = rating,
    reviewCount = reviewCount,
    distanceMeters = distanceMeters,
    isOpenNow = isOpenNow,
    address = address,
    point = point,
    isRecommended = isRecommended,
)

/**
 * Репозиторий-фейк для тестов ViewModel: очередь ответов по страницам, чтобы
 * можно было проверить и пагинацию, и ошибку на второй странице.
 */
class FakeCatalogRepository : CatalogRepository {

    var pages: MutableMap<Int, ApiResult<PlacePage>> = mutableMapOf()
    var details: ApiResult<PlaceDetails> = ApiResult.Failure(ApiError.NotFound)
    var reviews: ApiResult<List<Review>> = ApiResult.Success(emptyList())

    val requestedFilters: MutableList<Pair<DiscoveryFilters, Int>> = mutableListOf()

    fun respondWith(items: List<Place>, page: Int = 0, hasMore: Boolean = false, fromCache: Boolean = false) {
        pages[page] = ApiResult.Success(PlacePage(items, page, hasMore, fromCache))
    }

    fun failWith(error: ApiError, page: Int = 0) {
        pages[page] = ApiResult.Failure(error)
    }

    override suspend fun places(filters: DiscoveryFilters, page: Int): ApiResult<PlacePage> {
        requestedFilters += filters to page
        return pages[page] ?: ApiResult.Failure(ApiError.NotFound)
    }

    override suspend fun placeDetails(placeId: String): ApiResult<PlaceDetails> = details

    override suspend fun reviews(placeId: String, page: Int): ApiResult<List<Review>> = reviews
}

/**
 * DAO-фейк вместо Room: тесты репозитория проверяют правила фоллбэка, а не
 * SQL — база здесь только замедлила бы прогон. Сам DAO покрыт отдельно на
 * Robolectric (`MahallaDatabaseTest`).
 */
class FakePlaceDao : PlaceDao {

    private val rows = MutableStateFlow<Map<String, PlaceEntity>>(emptyMap())

    var upsertCount: Int = 0
        private set

    var deleteStaleThreshold: Long? = null
        private set

    fun seed(entities: List<PlaceEntity>) {
        rows.value = entities.associateBy(PlaceEntity::id)
    }

    fun current(): List<PlaceEntity> = sorted(rows.value.values)

    override fun observeAll(): Flow<List<PlaceEntity>> = rows.map { sorted(it.values) }

    override fun observeByCategory(category: String): Flow<List<PlaceEntity>> =
        rows.map { entities -> sorted(entities.values.filter { it.category == category }) }

    override suspend fun nearest(limit: Int): List<PlaceEntity> = current().take(limit)

    override suspend fun nearestByCategory(category: String, limit: Int): List<PlaceEntity> =
        current().filter { it.category == category }.take(limit)

    override suspend fun byId(id: String): PlaceEntity? = rows.value[id]

    override suspend fun upsert(places: List<PlaceEntity>) {
        upsertCount++
        rows.value = rows.value + places.associateBy(PlaceEntity::id)
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun deleteStale(updatedBeforeEpochSeconds: Long) {
        deleteStaleThreshold = updatedBeforeEpochSeconds
        rows.value = rows.value.filterValues { it.updatedAtEpochSeconds >= updatedBeforeEpochSeconds }
    }

    private fun sorted(entities: Collection<PlaceEntity>): List<PlaceEntity> =
        entities.sortedWith(compareBy(PlaceEntity::distanceMeters, PlaceEntity::id))
}
