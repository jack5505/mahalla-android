package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.entity.CartDraftItemEntity

/** [CartDraftDao] в памяти: без Room и без Robolectric, для plain JVM тестов. */
class FakeCartDraftDao : CartDraftDao {

    private val rows = MutableStateFlow<List<CartDraftItemEntity>>(emptyList())

    override fun observe(placeId: String): Flow<List<CartDraftItemEntity>> =
        rows.map { list -> list.filter { it.placeId == placeId } }

    override suspend fun items(placeId: String): List<CartDraftItemEntity> =
        rows.value.filter { it.placeId == placeId }

    override suspend fun line(placeId: String, lineId: String): CartDraftItemEntity? =
        rows.value.find { it.placeId == placeId && it.lineId == lineId }

    override suspend fun total(placeId: String): Long? =
        rows.value.filter { it.placeId == placeId }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.priceSum * it.quantity }

    override suspend fun activePlaceId(): String? = rows.value.firstOrNull()?.placeId

    override suspend fun upsert(item: CartDraftItemEntity) {
        rows.value = rows.value.filterNot {
            it.placeId == item.placeId && it.lineId == item.lineId
        } + item
    }

    override suspend fun upsertAll(items: List<CartDraftItemEntity>) {
        items.forEach { upsert(it) }
    }

    override suspend fun remove(placeId: String, lineId: String) {
        rows.value = rows.value.filterNot { it.placeId == placeId && it.lineId == lineId }
    }

    override suspend fun clear(placeId: String) {
        rows.value = rows.value.filterNot { it.placeId == placeId }
    }

    override suspend fun clearAll() {
        rows.value = emptyList()
    }
}
