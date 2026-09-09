package uz.mahalla.feature.booking.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.contract.ContractSample

/**
 * Сверка BookingApi с живым стендом (пилот контрактных проб, см.
 * `contract/booking.sh` и `docs/API-CONTRACT.md`).
 *
 * Отличие от [BookingRepositoryTest]: тот проверяет поведение клиента на
 * заранее написанных ответах MockWebServer, то есть закрепляет то, что мы
 * **думаем** про бэкенд. Этот — разбирает ответы, снятые с реального стенда,
 * и потому ловит расхождение с тем, что бэкенд шлёт на самом деле.
 *
 * Проба не снята — тест пропускается, а не краснеет: стенд доступен не всегда,
 * а `main` должен оставаться зелёным. Снять пробы: `contract/booking.sh`.
 */
class BookingContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("booking", name)
        assumeTrue("проба «$name» не снята — запусти contract/booking.sh", root != null)
        return root!!
    }

    /**
     * @param allowedAbsent поля, которых в ответе законно может не быть.
     * Пустой список объектов — не «всё сошлось», а пустая проба: сверять
     * нечего, и молчаливый зелёный тут хуже красного.
     */
    private fun assertFieldsMatch(
        objects: List<JsonObject>,
        serializer: kotlinx.serialization.KSerializer<*>,
        what: String,
        allowedAbsent: Set<String> = emptySet(),
    ) {
        assertTrue("в пробе нет ни одного объекта ($what) — сверять нечего", objects.isNotEmpty())
        assertEquals(
            "стенд шлёт поля ($what), которых нет в DTO — они молча теряются",
            emptySet<String>(),
            ContractSample.unknownToClient(objects, serializer),
        )
        assertEquals(
            "DTO объявляет поля ($what), которых стенд ни разу не прислал — " +
                "или их убрали с бэкенда, или их там не было никогда",
            emptySet<String>(),
            ContractSample.declaredButAbsent(objects, serializer) - allowedAbsent,
        )
    }

    @Test
    fun `service fields from the stand match ServiceDto`() {
        val root = sampleOrSkip("services")
        val response = json.decodeFromJsonElement<ApiResponse<List<ServiceDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            ServiceDto.serializer(),
            what = "услуги",
            // Jackson отдаёт boolean-геттер то как isActive, то как active,
            // поэтому DTO объявляет оба, и одного в ответе не будет всегда.
            allowedAbsent = setOf("isActive", "active"),
        )
    }

    /**
     * Слоты объявлены как массив строк, хотя springdoc описывает `LocalTime`
     * объектом. Ошибись мы тут — разбор падал бы целиком, поэтому проверка
     * именно на декодирование в `List<String>`.
     */
    @Test
    fun `slots come back as plain strings, not objects`() {
        val root = sampleOrSkip("slots")
        val response = json.decodeFromJsonElement<ApiResponse<List<String>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        // На пустом дне разбор проходит всегда и ничего не доказывает —
        // это «не проверили», а не «строки подтверждены».
        assumeTrue("на этот день свободных слотов нет", response.data?.isNotEmpty() == true)
    }

    /**
     * Ради этой пробы всё и затевалось: сам факт её наличия означает, что
     * `POST appointments` принял тело в том виде, в каком его шлёт
     * [BookAppointmentRequest], — то есть имена полей, выведенные из
     * перекрытой коллизией схемы, угаданы верно.
     */
    @Test
    fun `a booking created on the stand matches AppointmentDto`() {
        val root = sampleOrSkip("book")
        val response = json.decodeFromJsonElement<ApiResponse<AppointmentDto>>(root)
        assertTrue(
            "стенд отказал на POST appointments: ${response.error} — " +
                "значит имена полей BookAppointmentRequest не совпали",
            response.success,
        )
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            AppointmentDto.serializer(),
            what = "записи",
        )
    }

    @Test
    fun `my appointments page matches AppointmentPageDto`() {
        val root = sampleOrSkip("my")
        val response = json.decodeFromJsonElement<ApiResponse<AppointmentPageDto>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        val page = root["data"]
        assertFieldsMatch(
            ContractSample.objectsIn(page),
            AppointmentPageDto.serializer(),
            what = "страницы записей",
        )

        // У аккаунта могло не быть ни одной записи — это не расхождение
        // контракта, а пустая страница: сверять поля тогда не на чем.
        val content = ContractSample.objectsIn((page as? JsonObject)?.get("content"))
        assumeTrue("страница записей пуста — поля сверять не на чем", content.isNotEmpty())
        assertFieldsMatch(content, AppointmentDto.serializer(), what = "записи внутри страницы")
    }
}
