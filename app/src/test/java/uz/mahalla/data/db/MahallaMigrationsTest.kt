package uz.mahalla.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.db.di.DatabaseModule
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.feature.food.domain.CartCalculator

/**
 * Миграции БД (issue #64): обновление приложения обязано сохранять черновик
 * корзины, а не пересоздавать базу.
 *
 * БД прежней версии собирается сырым SQL — ровно теми `CREATE TABLE`, что
 * выдавала Room в той версии, — и открывается **через production-конфигурацию**
 * (`DatabaseModule.provideDatabase`): проверять миграции на builder'е,
 * собранном тут же в тесте, значит не заметить, что в приложении миграции не
 * подключены.
 *
 * Схему после миграций проверяет сама Room: у файла нет `room_master_table`,
 * поэтому на открытии она сверяет фактические таблицы с ожидаемыми и падает,
 * если миграция что-то забыла. Отдельных assert'ов на столбцы для этого не
 * нужно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MahallaMigrationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        deleteDatabaseFiles()
    }

    @Test
    fun `cart draft survives upgrade from version 1`() = runTest {
        createLegacyDatabase(version = 1) { db ->
            db.execSQL(CREATE_PLACES_V1)
            db.execSQL(CREATE_PLACES_CATEGORY_INDEX)
            db.execSQL(CREATE_ORDERS)
            db.execSQL(CREATE_CART_DRAFT_ITEMS_V1)
            db.execSQL(
                "INSERT INTO `cart_draft_items` VALUES ('place-1', 'lagman', 'Lagman', 32000, 2)",
            )
            db.execSQL(
                "INSERT INTO `places` VALUES ('place-1', 'Osh markazi', 'FOOD', 4.6, 250, 1, 1700000000)",
            )
        }

        val database = DatabaseModule.provideDatabase(context)
        try {
            val lines = database.cartDraftDao().items("place-1")
            assertEquals(1, lines.size)
            val line = lines.single()
            assertEquals("lagman", line.productId)
            assertEquals(2, line.quantity)
            assertEquals(32_000L, line.priceSum)
            // Ключ строки без модификаторов равен id позиции — перенесённая
            // корзина складывается с новыми добавлениями, а не двоится.
            assertEquals(CartCalculator.lineId("lagman", emptySet()), line.lineId)
            assertEquals("", line.optionIds)
            assertEquals("", line.optionsLabel)
            assertEquals("", line.placeName)
            assertEquals(0L, line.deliverySum)

            // Кэш каталога тоже переезжает, а новые поля пусты, а не потеряны.
            val place = database.placeDao().byId("place-1")
            assertEquals("Osh markazi", place?.name)
            assertEquals(0, place?.reviewCount)
            assertEquals(false, place?.isRecommended)
            assertNull(place?.address)
            assertNull(place?.latitude)
            assertNull(place?.phone)
        } finally {
            database.close()
        }
    }

    @Test
    fun `cart draft and place details survive upgrade from version 2`() = runTest {
        createLegacyDatabase(version = 2) { db ->
            db.execSQL(CREATE_PLACES_V2)
            db.execSQL(CREATE_PLACES_CATEGORY_INDEX)
            db.execSQL(CREATE_ORDERS)
            db.execSQL(CREATE_CART_DRAFT_ITEMS_V1)
            db.execSQL(
                "INSERT INTO `cart_draft_items` VALUES ('place-2', 'somsa', 'Somsa', 12000, 3)",
            )
            db.execSQL(
                """
                INSERT INTO `places` VALUES (
                    'place-2', 'Non uyi', 'FOOD', 4.2, 800, 1, 1700000000,
                    17, 'Amir Temur 1', 'https://cdn/1.jpg', 41.31, 69.28, 1,
                    'Tandir non', '+998901112233', 'https://non.uz'
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO `orders` VALUES ('order-1', 'place-2', 'Non uyi', 'DELIVERING', 45000, 1700000100)",
            )
        }

        val database = DatabaseModule.provideDatabase(context)
        try {
            val line = database.cartDraftDao().items("place-2").single()
            assertEquals("somsa", line.lineId)
            assertEquals(3, line.quantity)

            val place = database.placeDao().byId("place-2")
            assertEquals(17, place?.reviewCount)
            assertEquals("Amir Temur 1", place?.address)
            assertEquals(41.31, place?.latitude ?: 0.0, 0.0001)
            assertEquals("+998901112233", place?.phone)
            assertEquals(true, place?.isRecommended)

            assertEquals("DELIVERING", database.orderDao().byId("order-1")?.status)
        } finally {
            database.close()
        }
    }

    /**
     * Перенесённая строка не двоится: добавление той же позиции без
     * модификаторов увеличивает количество, а не заводит вторую строку.
     */
    @Test
    fun `migrated line merges with a new addition of the same item`() = runTest {
        createLegacyDatabase(version = 2) { db ->
            db.execSQL(CREATE_PLACES_V2)
            db.execSQL(CREATE_PLACES_CATEGORY_INDEX)
            db.execSQL(CREATE_ORDERS)
            db.execSQL(CREATE_CART_DRAFT_ITEMS_V1)
            db.execSQL(
                "INSERT INTO `cart_draft_items` VALUES ('place-3', 'plov', 'Plov', 40000, 1)",
            )
        }

        val database = DatabaseModule.provideDatabase(context)
        try {
            val dao = database.cartDraftDao()
            dao.upsert(
                CartDraftItemEntity(
                    placeId = "place-3",
                    lineId = CartCalculator.lineId("plov", emptySet()),
                    productId = "plov",
                    name = "Plov",
                    priceSum = 40_000,
                    quantity = 2,
                ),
            )

            val lines = dao.items("place-3")
            assertEquals(1, lines.size)
            assertEquals(2, lines.single().quantity)
        } finally {
            database.close()
        }
    }

    /**
     * Понижение версии (сборка постарше поверх новой) пересоздаёт БД, а не
     * падает: старый код не знает схемы, которая уже лежит в файле.
     */
    @Test
    fun `downgrade recreates the database instead of crashing`() = runTest {
        val current = DatabaseModule.provideDatabase(context)
        try {
            current.cartDraftDao().upsert(
                CartDraftItemEntity(
                    placeId = "place-4",
                    lineId = "kebab",
                    productId = "kebab",
                    name = "Kebab",
                    priceSum = 25_000,
                    quantity = 1,
                ),
            )
        } finally {
            current.close()
        }
        openRawDatabase().use { it.version = MahallaDatabase.VERSION + 1 }

        val database = DatabaseModule.provideDatabase(context)
        try {
            assertTrue(database.cartDraftDao().items("place-4").isEmpty())
        } finally {
            database.close()
        }
    }

    /** Пропущенная миграция — это падение на устройстве, поэтому цепочка сплошная. */
    @Test
    fun `migrations cover every version up to the current one`() {
        val steps = MahallaMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(
            (1 until MahallaDatabase.VERSION).map { it to it + 1 },
            steps,
        )
    }

    private fun createLegacyDatabase(version: Int, schema: (SQLiteDatabase) -> Unit) {
        openRawDatabase().use { db ->
            schema(db)
            db.version = version
        }
    }

    private fun openRawDatabase(): SQLiteDatabase {
        val file = context.getDatabasePath(MahallaDatabase.NAME)
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null)
    }

    /** Файл БД общий для всех тестов класса — иначе миграция «переезжала» бы поверх чужой схемы. */
    private fun deleteDatabaseFiles() {
        context.deleteDatabase(MahallaDatabase.NAME)
    }

    private companion object {
        const val CREATE_PLACES_V1 =
            "CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `rating` REAL NOT NULL, `distanceMeters` INTEGER NOT NULL, " +
                "`isOpenNow` INTEGER NOT NULL, `updatedAtEpochSeconds` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"

        const val CREATE_PLACES_V2 =
            "CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `rating` REAL NOT NULL, `distanceMeters` INTEGER NOT NULL, " +
                "`isOpenNow` INTEGER NOT NULL, `updatedAtEpochSeconds` INTEGER NOT NULL, " +
                "`reviewCount` INTEGER NOT NULL, `address` TEXT, `photoUrl` TEXT, `latitude` REAL, " +
                "`longitude` REAL, `isRecommended` INTEGER NOT NULL, `description` TEXT, " +
                "`phone` TEXT, `website` TEXT, PRIMARY KEY(`id`))"

        const val CREATE_PLACES_CATEGORY_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_places_category` ON `places` (`category`)"

        const val CREATE_ORDERS =
            "CREATE TABLE IF NOT EXISTS `orders` (`id` TEXT NOT NULL, `placeId` TEXT NOT NULL, " +
                "`placeName` TEXT NOT NULL, `status` TEXT NOT NULL, `totalSum` INTEGER NOT NULL, " +
                "`createdAtEpochSeconds` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val CREATE_CART_DRAFT_ITEMS_V1 =
            "CREATE TABLE IF NOT EXISTS `cart_draft_items` (`placeId` TEXT NOT NULL, " +
                "`productId` TEXT NOT NULL, `name` TEXT NOT NULL, `priceSum` INTEGER NOT NULL, " +
                "`quantity` INTEGER NOT NULL, PRIMARY KEY(`placeId`, `productId`))"
    }
}
