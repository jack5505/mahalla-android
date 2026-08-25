package uz.mahalla.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.db.entity.PlaceEntity

/**
 * DAO-тесты на Robolectric (эмулятора в CI нет). `application = Application`
 * намеренно: реальный `@HiltAndroidApp`-класс здесь не нужен, и граф Hilt не
 * должен влиять на тесты БД.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MahallaDatabaseTest {

    private lateinit var database: MahallaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MahallaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `places are sorted by distance and filtered by category`() = runTest {
        val dao = database.placeDao()
        dao.upsert(
            listOf(
                place(id = "far-food", category = "food", distanceMeters = 900),
                place(id = "near-pharmacy", category = "pharmacy", distanceMeters = 120),
                place(id = "near-food", category = "food", distanceMeters = 300),
            ),
        )

        assertEquals(
            listOf("near-pharmacy", "near-food", "far-food"),
            dao.observeAll().first().map(PlaceEntity::id),
        )
        assertEquals(
            listOf("near-food", "far-food"),
            dao.observeByCategory("food").first().map(PlaceEntity::id),
        )
    }

    @Test
    fun `upsert refreshes an existing place instead of duplicating it`() = runTest {
        val dao = database.placeDao()
        dao.upsert(listOf(place(id = "p-1", distanceMeters = 900)))

        dao.upsert(listOf(place(id = "p-1", distanceMeters = 120)))

        assertEquals(1, dao.observeAll().first().size)
        assertEquals(120, dao.byId("p-1")?.distanceMeters)
    }

    @Test
    fun `clearing the catalog cache leaves no rows`() = runTest {
        val dao = database.placeDao()
        dao.upsert(listOf(place(id = "p-1"), place(id = "p-2")))

        dao.clear()

        assertEquals(emptyList<PlaceEntity>(), dao.observeAll().first())
        assertNull(dao.byId("p-1"))
    }

    @Test
    fun `orders are returned newest first`() = runTest {
        val dao = database.orderDao()
        dao.upsert(
            listOf(
                order(id = "old", createdAtEpochSeconds = 1_000),
                order(id = "new", createdAtEpochSeconds = 3_000),
                order(id = "middle", createdAtEpochSeconds = 2_000),
            ),
        )

        assertEquals(
            listOf("new", "middle", "old"),
            dao.observeAll().first().map(OrderEntity::id),
        )
    }

    @Test
    fun `cart draft survives per place and counts the total`() = runTest {
        val dao = database.cartDraftDao()
        dao.upsert(CartDraftItemEntity("place-1", "osh", "Osh", priceSum = 30_000, quantity = 2))
        dao.upsert(CartDraftItemEntity("place-1", "cola", "Cola", priceSum = 8_000, quantity = 1))
        dao.upsert(CartDraftItemEntity("place-2", "somsa", "Somsa", priceSum = 12_000, quantity = 3))

        assertEquals(68_000L, dao.total("place-1"))
        assertEquals(36_000L, dao.total("place-2"))
        assertEquals(listOf("cola", "osh"), dao.observe("place-1").first().map { it.productId })
    }

    @Test
    fun `changing quantity does not add a second row`() = runTest {
        val dao = database.cartDraftDao()
        dao.upsert(CartDraftItemEntity("place-1", "osh", "Osh", priceSum = 30_000, quantity = 1))

        dao.upsert(CartDraftItemEntity("place-1", "osh", "Osh", priceSum = 30_000, quantity = 4))

        assertEquals(1, dao.observe("place-1").first().size)
        assertEquals(120_000L, dao.total("place-1"))
    }

    @Test
    fun `draft is cleared only for the requested place`() = runTest {
        val dao = database.cartDraftDao()
        dao.upsert(CartDraftItemEntity("place-1", "osh", "Osh", priceSum = 30_000, quantity = 1))
        dao.upsert(CartDraftItemEntity("place-2", "somsa", "Somsa", priceSum = 12_000, quantity = 1))

        dao.clear("place-1")

        assertNull(dao.total("place-1"))
        assertEquals(12_000L, dao.total("place-2"))
    }

    @Test
    fun `removing a single item keeps the rest of the draft`() = runTest {
        val dao = database.cartDraftDao()
        dao.upsert(CartDraftItemEntity("place-1", "osh", "Osh", priceSum = 30_000, quantity = 1))
        dao.upsert(CartDraftItemEntity("place-1", "cola", "Cola", priceSum = 8_000, quantity = 1))

        dao.remove("place-1", "osh")

        assertEquals(listOf("cola"), dao.observe("place-1").first().map { it.productId })
    }

    private fun place(
        id: String,
        category: String = "food",
        distanceMeters: Int = 500,
    ) = PlaceEntity(
        id = id,
        name = "Place $id",
        category = category,
        rating = 4.5,
        distanceMeters = distanceMeters,
        isOpenNow = true,
        updatedAtEpochSeconds = 1_774_000_000L,
    )

    private fun order(id: String, createdAtEpochSeconds: Long) = OrderEntity(
        id = id,
        placeId = "place-1",
        placeName = "Osh markazi",
        status = "NEW",
        totalSum = 50_000,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )
}
