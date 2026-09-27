package uz.mahalla.feature.discovery.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.contract.ContractSample
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceCategoryCatalog

/**
 * Сверка [CategoriesApi] с живым стендом (issue #378, хвосты — issue #382).
 *
 * Ради этого теста всё и затевалось. Схема `GET categories` снималась не с
 * `/v3/api-docs` — по хосту стенда его нет, nginx отдаёт `404`, — а из
 * исходников вмерженного бэкенд-PR. Пока ответ не снят с живой ручки, имена
 * четырёх полей оставались догадкой, а ошибка в них не уронила бы ни один
 * тест: разбор мягкий, `sortOrder` с дефолтом `0` молча переставил бы плитки
 * в алфавитный порядок.
 *
 * Отличие от [CategoryRepositoryTest]: тот гоняет поведение клиента на
 * заранее написанных ответах MockWebServer, то есть закрепляет то, что мы
 * **думаем** про бэкенд. Этот разбирает ответ, снятый со стенда.
 *
 * Проба не снята — тест пропускается, а не краснеет. Снять: `contract/categories.sh`.
 */
class CategoriesContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("categories", name)
        assumeTrue("проба «$name» не снята — запусти contract/categories.sh", root != null)
        return root!!
    }

    @Test
    fun `category fields from the stand match CategoryDto`() {
        val root = sampleOrSkip("categories")
        val response = json.decodeFromJsonElement<ApiResponse<List<CategoryDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        val objects = ContractSample.objectsIn(root["data"])
        assertTrue("в пробе нет ни одной категории — сверять нечего", objects.isNotEmpty())
        assertEquals(
            "стенд шлёт поля категории, которых нет в CategoryDto — они молча теряются",
            emptySet<String>(),
            ContractSample.unknownToClient(objects, CategoryDto.serializer()),
        )
        assertEquals(
            "CategoryDto объявляет поля, которых стенд ни разу не прислал",
            emptySet<String>(),
            ContractSample.declaredButAbsent(objects, CategoryDto.serializer()),
        )
    }

    /**
     * Самая дорогая ошибка была бы не в разборе, а в имени `sortOrder`: не
     * совпади оно, все категории приехали бы с нулём, и плитки встали бы по
     * алфавиту кода вместо порядка дашборда — без единой ошибки на экране.
     * Поэтому проверяется не наличие поля, а то, что значения действительно
     * различаются и строго растут.
     */
    @Test
    fun `sortOrder really orders the catalog`() {
        val items = categoriesFromStand()
        val orders = items.map { it.sortOrder }
        assertEquals(
            "sortOrder у всех одинаковый — поле либо переименовано, либо не заполнено",
            orders.sorted().distinct(),
            orders,
        )
    }

    /**
     * Стенд отдаёт и коды, которых в приложении нет. Тест закрепляет, что
     * плитки из живого ответа собираются ровно те, что приложение умеет
     * показывать, — и в порядке сервера.
     */
    @Test
    fun `the live catalog resolves to the tiles the app can show`() {
        val codes = categoriesFromStand().mapNotNull { it.code }
        val tiles = PlaceCategoryCatalog.resolve(codes)

        assertEquals(
            "живой каталог перестал давать те же плитки — сверь коды с PlaceCategory",
            listOf(
                PlaceCategory.Food,
                PlaceCategory.Pharmacy,
                PlaceCategory.Hospital,
                PlaceCategory.Cinema,
                PlaceCategory.Playground,
                PlaceCategory.Master,
                PlaceCategory.Fashion,
            ),
            tiles,
        )
        // BARBER и FREELANCER оба приезжают со стенда и оба «мастер» —
        // дубликата в плитках быть не должно.
        assertEquals(tiles.distinct(), tiles)
    }

    /**
     * Ручка публичная: гео-заголовки, обязательные на `places/nearby`, здесь не
     * нужны. Начни она их требовать — плитки пропали бы у всех, кто не дал
     * геолокацию, поэтому проба снимается отдельно и сверяется с основной.
     */
    @Test
    fun `the endpoint answers the same without geo headers`() {
        val withGeo = categoriesFromStand()
        val root = sampleOrSkip("categories_without_geo")
        val response = json.decodeFromJsonElement<ApiResponse<List<CategoryDto>>>(root)
        assertTrue("без гео-заголовков конверт с success=false: ${response.error}", response.success)
        assertEquals(withGeo.mapNotNull { it.code }, response.data.orEmpty().mapNotNull { it.code })
    }

    private fun categoriesFromStand(): List<CategoryDto> {
        val root = sampleOrSkip("categories")
        val items = json.decodeFromJsonElement<ApiResponse<List<CategoryDto>>>(root).data.orEmpty()
        assertTrue("проба пуста — сверять нечего", items.isNotEmpty())
        return items
    }
}
