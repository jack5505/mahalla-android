package uz.mahalla.feature.discovery.data

import android.app.Application
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.db.MahallaDatabase
import uz.mahalla.data.db.dao.PlaceCategoryDao
import uz.mahalla.data.db.entity.PlaceCategoryEntity
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.domain.PlaceCategory

/**
 * Кэш категорий (issue #378) на настоящем стеке: Room в памяти и
 * [MockWebServer] через [NetworkFactory]. Фейк Retrofit не поймал бы ни
 * ошибку в пути, ни расхождение с именами полей `CategoryItem`.
 *
 * Тело ответа — по схеме jack5505/mahalla#340 (`code`, `titleUz`, `titleRu`,
 * `sortOrder` в конверте `ApiResponse`). Robolectric нужен только ради Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CategoryRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var database: MahallaDatabase
    private lateinit var repository: DefaultCategoryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MahallaDatabase::class.java,
        ).build()
        repository = DefaultCategoryRepository(
            api = NetworkFactory
                .retrofit(
                    server.url("/").toString(),
                    NetworkFactory.clientBuilder().build(),
                    NetworkFactory.converterFactory(NetworkFactory.json()),
                )
                .create(CategoriesApi::class.java),
            dao = database.placeCategoryDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun `an empty cache falls back to the built-in set`() = runTest {
        assertEquals(PlaceCategory.selectable, repository.categories().first())
    }

    @Test
    fun `refresh asks GET categories and stores the list in server order`() = runTest {
        server.enqueue(
            envelope(
                """[{"code":"PHARMACY","titleUz":"Dorixona","titleRu":"Аптека","sortOrder":20},
                    {"code":"FOOD","titleUz":"Ovqat","titleRu":"Еда","sortOrder":10}]""",
            ),
        )

        val result = repository.refresh()

        assertTrue(result is ApiResult.Success)
        assertEquals("/categories", server.takeRequest().path)
        // Порядок — по sortOrder, а не по порядку в ответе и не по перечислению.
        assertEquals(listOf(PlaceCategory.Food, PlaceCategory.Pharmacy), repository.categories().first())
        assertEquals(
            listOf(
                PlaceCategoryEntity("FOOD", "Ovqat", "Еда", 10),
                PlaceCategoryEntity("PHARMACY", "Dorixona", "Аптека", 20),
            ),
            database.placeCategoryDao().all(),
        )
    }

    @Test
    fun `unknown codes are cached but not shown`() = runTest {
        server.enqueue(envelope("""[{"code":"BAKERY","sortOrder":5},{"code":"FOOD","sortOrder":10}]"""))

        repository.refresh()

        assertEquals(listOf(PlaceCategory.Food), repository.categories().first())
        // В кэше код остаётся: появится плитка — появится и категория.
        assertEquals(listOf("BAKERY", "FOOD"), database.placeCategoryDao().all().map { it.code })
    }

    @Test
    fun `an item without a code is skipped, the rest survives`() = runTest {
        server.enqueue(envelope("""[{"titleUz":"?","sortOrder":1},{"code":"CINEMA","sortOrder":40}]"""))

        assertTrue(repository.refresh() is ApiResult.Success)

        assertEquals(listOf(PlaceCategory.Cinema), repository.categories().first())
    }

    @Test
    fun `an explicit null code is skipped, not a parse failure`() = runTest {
        server.enqueue(envelope("""[{"code":null,"sortOrder":1},{"code":"CINEMA","sortOrder":40}]"""))

        assertTrue(repository.refresh() is ApiResult.Success)

        assertEquals(listOf(PlaceCategory.Cinema), repository.categories().first())
    }

    @Test
    fun `a successful answer replaces the cache entirely`() = runTest {
        server.enqueue(envelope("""[{"code":"FOOD","sortOrder":10},{"code":"HOSPITAL","sortOrder":30}]"""))
        repository.refresh()

        // Дашборд выключил клинику — из следующего ответа она пропала.
        server.enqueue(envelope("""[{"code":"FOOD","sortOrder":10}]"""))
        repository.refresh()

        assertEquals(listOf(PlaceCategory.Food), repository.categories().first())
    }

    @Test
    fun `a network failure keeps the previous cache`() = runTest {
        server.enqueue(envelope("""[{"code":"FOOD","sortOrder":10}]"""))
        repository.refresh()

        server.enqueue(MockResponse().setResponseCode(500))
        val result = repository.refresh()

        assertTrue(result is ApiResult.Failure)
        assertEquals(listOf(PlaceCategory.Food), repository.categories().first())
    }

    @Test
    fun `success false in the envelope is a failure and leaves the cache alone`() = runTest {
        server.enqueue(envelope("""[{"code":"FOOD","sortOrder":10}]"""))
        repository.refresh()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"INTERNAL","message":"x"}}"""),
        )
        val result = repository.refresh()

        assertTrue(result is ApiResult.Failure)
        assertEquals(listOf(PlaceCategory.Food), repository.categories().first())
    }

    /**
     * Запись в кэш — фоновая работа главной: отказ базы обязан вернуться
     * `Failure`, а не исключением, которое отменит загрузку каталога.
     */
    @Test
    fun `a database failure while storing is a failure, not an exception`() = runTest {
        server.enqueue(envelope("""[{"code":"FOOD","sortOrder":10}]"""))
        val broken = DefaultCategoryRepository(
            api = NetworkFactory
                .retrofit(
                    server.url("/").toString(),
                    NetworkFactory.clientBuilder().build(),
                    NetworkFactory.converterFactory(NetworkFactory.json()),
                )
                .create(CategoriesApi::class.java),
            dao = object : PlaceCategoryDao() {
                override fun observeAll(): Flow<List<PlaceCategoryEntity>> = flowOf(emptyList())
                override suspend fun all(): List<PlaceCategoryEntity> = emptyList()
                override suspend fun insert(items: List<PlaceCategoryEntity>) = Unit
                override suspend fun clear() = Unit
                override suspend fun replaceAll(items: List<PlaceCategoryEntity>) {
                    throw SQLiteFullException("disk full")
                }
            },
        )

        val result = broken.refresh()

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.Unexpected)
    }

    private fun envelope(data: String) = MockResponse()
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data,"timestamp":"2026-09-26T12:00:00Z"}""")
}
