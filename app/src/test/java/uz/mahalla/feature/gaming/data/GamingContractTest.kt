package uz.mahalla.feature.gaming.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.contract.ContractSample
import uz.mahalla.feature.gaming.domain.GamingZoneType

/**
 * Сверка GamingApi с живым стендом (issue #425, по образцу `contract/booking.sh`
 * и `BookingContractTest`).
 *
 * Схема только что поменялась (issue #406: `totalSeats` → `totalUnits`,
 * `zoneType` — закрытый справочник, новая ручка `zones/{zoneId}/units`), и
 * следующий бэкенд-шаг V42 (issue #409) поменяет её снова. Подтверждение в
 * `docs/API-CONTRACT.md` до этой задачи было разовыми curl'ами от 2026-10-02,
 * переписанными в документ руками, — этот тест разбирает тот же стенд, но
 * машинно и на каждом прогоне.
 *
 * Отличие от [uz.mahalla.feature.gaming.data.GamingRepositoryTest]: тот
 * закрепляет, что клиент **думает** про ответ MockWebServer, этот — разбирает
 * ответ, снятый с реального стенда `contract/gaming.sh`.
 *
 * Проба не снята — тест пропускается, а не краснеет: стенд поднимается не
 * всегда, а `main` должен оставаться зелёным.
 */
class GamingContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("gaming", name)
        assumeTrue("проба «$name» не снята — запусти contract/gaming.sh", root != null)
        return root!!
    }

    /**
     * @param allowedAbsent поля, которых в ответе законно может не быть.
     * Пустой список объектов — не «всё сошлось», а пустая проба: сверять
     * нечего, и молчаливый зелёный тут хуже красного.
     */
    private fun assertFieldsMatch(
        objects: List<JsonObject>,
        serializer: KSerializer<*>,
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

    private fun zonesFromStand(): List<GamingZoneDto> {
        val root = sampleOrSkip("zones")
        val response = json.decodeFromJsonElement<ApiResponse<List<GamingZoneDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        return response.data.orEmpty()
    }

    @Test
    fun `zone fields from the stand match GamingZoneDto`() {
        val root = sampleOrSkip("zones")
        val response = json.decodeFromJsonElement<ApiResponse<List<GamingZoneDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            GamingZoneDto.serializer(),
            what = "зоны",
            // Jackson отдаёт boolean-геттер то как isAvailable, то как
            // available (KDoc GamingZoneDto) — одного из двух в ответе не
            // будет всегда.
            allowedAbsent = setOf("isAvailable", "available"),
        )
    }

    /**
     * Самая дорогая ошибка здесь была бы не в разборе, а в имени поля: не
     * совпади `totalUnits` с тем, что реально шлёт стенд, все зоны молча
     * показали бы «мест нет» вместо настоящего числа (issue #406).
     */
    @Test
    fun `totalUnits is a count of real units, not an estimate`() {
        val zones = zonesFromStand()
        assumeTrue("проба пуста — сверять нечего", zones.isNotEmpty())
        assertTrue(
            "ни у одной зоны нет totalUnits — поле переименовано или пропало (issue #406)",
            zones.any { it.totalUnits != null },
        )
    }

    /**
     * `zoneType` — закрытый справочник (issue #406): известное значение
     * должно разобраться в своё перечисление, а незнакомое — упасть в
     * [GamingZoneType.Other], а не потеряться молча. Разбор — дело маппера
     * ([GamingZoneDto.toDomain]), не сериализатора.
     */
    @Test
    fun `zoneType maps into the domain vocabulary, unknown values fall back to OTHER`() {
        val zones = zonesFromStand()
        assumeTrue("проба пуста — сверять нечего", zones.isNotEmpty())

        val knownValues = GamingZoneType.entries.map { it.apiValue }.toSet()
        // Зону без id маппер отбрасывает целиком (см. GamingMappers.toDomain) —
        // по причинам, не связанным с zoneType. Без фильтра `domain` ушёл бы в
        // `null` и по этой причине тоже, и ветка «зона без zoneType» ниже
        // сошлась бы на пустом месте, ничего не проверив.
        val withId = zones.filterNot { it.id.isNullOrBlank() }
        assumeTrue("ни у одной зоны в пробе нет id — маппер их все отбросит", withId.isNotEmpty())
        withId.forEach { dto ->
            val domain = requireNotNull(dto.toDomain(placeId = dto.placeId.orEmpty())) {
                "зона с id «${dto.id}» не должна была отброситься маппером"
            }
            when (val rawType = dto.zoneType?.trim()?.uppercase()) {
                null, "" -> assertEquals(
                    "зона без zoneType не должна получить тип по умолчанию",
                    null,
                    domain.zoneType,
                )
                in knownValues -> assertTrue(
                    "известное значение zoneType «$rawType» не разобралось",
                    domain.zoneType != null,
                )
                else -> assertEquals(
                    "незнакомое значение zoneType «$rawType» должно упасть в OTHER, а не потеряться",
                    GamingZoneType.Other,
                    domain.zoneType,
                )
            }
        }
    }

    /**
     * Места зоны (issue #406) — `{id, zoneId, number, seats}`, сверяются тем
     * же приёмом, что и зоны.
     */
    @Test
    fun `unit fields from the stand match GamingUnitDto`() {
        val root = sampleOrSkip("units")
        val response = json.decodeFromJsonElement<ApiResponse<List<GamingUnitDto>>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            GamingUnitDto.serializer(),
            what = "места зоны",
        )
    }

    /**
     * Ради этой пробы всё и затевалось (PR #416 оставил её «кандидатом»):
     * ответ `GET gaming/bookings/my` под токеном ни разу не был снят.
     */
    @Test
    fun `my bookings page matches GamingBookingPageDto`() {
        val root = sampleOrSkip("my")
        val response = json.decodeFromJsonElement<ApiResponse<GamingBookingPageDto>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        val page = root["data"]
        assertFieldsMatch(
            ContractSample.objectsIn(page),
            GamingBookingPageDto.serializer(),
            what = "страницы броней",
        )

        // Аккаунт мог ни разу не забронировать зону — это не расхождение
        // контракта, а пустая страница: сверять поля тогда не на чем.
        val content = ContractSample.objectsIn((page as? JsonObject)?.get("content"))
        assumeTrue("страница броней пуста — поля сверять не на чем", content.isNotEmpty())
        assertFieldsMatch(content, GamingBookingDto.serializer(), what = "брони внутри страницы")
    }
}
