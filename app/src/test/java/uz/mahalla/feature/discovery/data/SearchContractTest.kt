package uz.mahalla.feature.discovery.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.contract.ContractSample

/**
 * Сверка [CatalogApi.search] с живым стендом (issue #387).
 *
 * `CatalogApi.search` был объявлен `ApiResponse<List<PlaceDocumentDto>>` ещё с
 * issue #53 (2026-08-29). Бэкенд с issue jack5505/mahalla#204 (2026-09-09)
 * отдаёт страницу (`PageResponse`): `data` — объект `{content, page, size,
 * totalElements, …}`, а не массив. Разбор списком на таком ответе падал в
 * [uz.mahalla.core.result.ApiError.Serialization], и `CatalogRepository`
 * маскировал это кэшем — поиск по сети не работал вообще, а экран лгал про
 * «показаны сохранённые данные». Этот тест разбирает ответ, снятый со стенда,
 * тем же путём, что и прод (`PageDto<PlaceDocumentDto>`), — если контракт
 * снова разойдётся, здесь будет видно по имени, а не по красному репорту
 * пользователя.
 *
 * Проба не снята — тест пропускается, а не краснеет. Снять: `contract/search.sh`.
 */
class SearchContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("search", name)
        assumeTrue("проба «$name» не снята — запусти contract/search.sh", root != null)
        return root!!
    }

    @Test
    fun `an empty content parses as success, not a serialization failure`() {
        // Ровно симптом issue #387: пустая выдача — валидный ответ, а не
        // повод уйти в ApiError.Serialization и подменить его кэшем.
        val root = sampleOrSkip("search_empty")
        val response = json.decodeFromJsonElement<ApiResponse<PageDto<PlaceDocumentDto>>>(root)

        assertTrue("конверт с success=false: ${response.error}", response.success)
        assertEquals(emptyList<PlaceDocumentDto>(), response.data?.content)
    }

    @Test
    fun `document fields from the stand match PlaceDocumentDto`() {
        val root = sampleOrSkip("search_hits")
        val response = json.decodeFromJsonElement<ApiResponse<PageDto<PlaceDocumentDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        val content = response.data?.content.orEmpty()
        assertTrue("в пробе нет ни одного документа — сверять нечего", content.isNotEmpty())

        val dataElement = root["data"]?.jsonObject?.get("content")
        val objects = ContractSample.objectsIn(dataElement)
        assertEquals(
            "стенд шлёт поля документа, которых нет в PlaceDocumentDto — они молча теряются",
            emptySet<String>(),
            ContractSample.unknownToClient(objects, PlaceDocumentDto.serializer()) - IGNORED_UNKNOWN,
        )
    }

    @Test
    fun `the endpoint requires geo headers, unlike categories`() {
        // В отличие от `categories`, у `search` гео-заголовки обязательны —
        // без них 403 GEO_PERMISSION_REQUIRED (contract/search.sh снимает это
        // отдельной пробой, а не полагается на память).
        val root = sampleOrSkip("search_without_geo")
        val response = json.decodeFromJsonElement<ApiResponse<PageDto<PlaceDocumentDto>>>(root)

        assertTrue("ожидали отказ без гео-заголовков", !response.success)
        assertEquals("GEO_PERMISSION_REQUIRED", response.error?.code)
    }

    private companion object {
        /**
         * `createdAt` в ответе есть (пока всегда `null`), но приложению не
         * нужен: даты создания заведения нигде не показываются. Не считаем
         * это «молча потерянным полем».
         */
        val IGNORED_UNKNOWN = setOf("createdAt")
    }
}
