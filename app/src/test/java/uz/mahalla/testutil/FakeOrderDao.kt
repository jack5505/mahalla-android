package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.entity.OrderEntity

/** [OrderDao] в памяти: без Room и без Robolectric, для plain JVM тестов. */
class FakeOrderDao : OrderDao {

    private val rows = MutableStateFlow<List<OrderEntity>>(emptyList())

    override fun observeAll(): Flow<List<OrderEntity>> = rows

    override suspend fun byId(id: String): OrderEntity? = rows.value.find { it.id == id }

    override suspend fun upsert(orders: List<OrderEntity>) {
        val byId = rows.value.associateBy { it.id }.toMutableMap()
        orders.forEach { byId[it.id] = it }
        rows.value = byId.values.toList()
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}
