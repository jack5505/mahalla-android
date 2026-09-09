package uz.mahalla.feature.security.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.auth.CheckSessionResponse
import uz.mahalla.data.network.contract.ContractSample
import uz.mahalla.data.network.pin.PinStatusDto

/**
 * Сверка `PinApi` и `SessionApi` с живым стендом (issue #102, пробы —
 * `contract/security.sh`).
 *
 * Отличие от [SecurityRepositoryTest]: тот проверяет поведение клиента на
 * ответах MockWebServer, то есть закрепляет то, что мы **думаем** про бэкенд.
 * Этот разбирает ответы, снятые со стенда.
 *
 * Проба не снята — тест пропускается, а не краснеет: стенд доступен не всегда.
 * Меняющие ручки (`pin/change`, `pin/biometric`, `pin-resume`) пробой не
 * покрыты намеренно — см. заголовок `contract/security.sh`.
 */
class SecurityContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("security", name)
        assumeTrue("проба «$name» не снята — запусти contract/security.sh", root != null)
        return root!!
    }

    /**
     * Ради чего проба вообще есть: обе ручки требуют Bearer, и от этого зависит
     * выбор Retrofit. Собери их на «голом» `@RefreshClient` — и 401 на них
     * останется без refresh; собери анонимные `pin-login`/`setup-pin` на
     * основном — и 401 уйдёт в рекурсию (`.claude/rules/network.md`).
     */
    @Test
    fun `pin status refuses an anonymous caller`() {
        val root = sampleOrSkip("unauthorized_status")
        val response = json.decodeFromJsonElement<ApiResponse<PinStatusDto>>(root)

        assertFalse("стенд ответил на pin/status без токена — ручка стала анонимной", response.success)
        assertEquals("UNAUTHORIZED", response.error?.code)
    }

    @Test
    fun `session check refuses an anonymous caller`() {
        val root = sampleOrSkip("unauthorized_session_check")
        val response = json.decodeFromJsonElement<ApiResponse<CheckSessionResponse>>(root)

        assertFalse(
            "стенд ответил на auth/session/check без токена — значит ручка " +
                "анонимна, как pin-login, и API надо переносить на @RefreshClient",
            response.success,
        )
        assertEquals("UNAUTHORIZED", response.error?.code)
    }

    @Test
    fun `pin status fields from the stand match PinStatusDto`() {
        val root = sampleOrSkip("status")
        val response = json.decodeFromJsonElement<ApiResponse<PinStatusDto>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            PinStatusDto.serializer(),
            what = "состояние PIN",
        )
    }

    @Test
    fun `session check fields from the stand match CheckSessionResponse`() {
        val root = sampleOrSkip("session_check")
        val response = json.decodeFromJsonElement<ApiResponse<CheckSessionResponse>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)

        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            CheckSessionResponse.serializer(),
            what = "проверку сессии",
            // `user` приходит только когда сессия жива; `reason` — наоборот,
            // только когда с ней что-то не так. Оба сразу не бывают никогда.
            allowedAbsent = setOf("user", "reason"),
        )
    }

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
            "DTO объявляет поля ($what), которых стенд ни разу не прислал",
            emptySet<String>(),
            ContractSample.declaredButAbsent(objects, serializer) - allowedAbsent,
        )
    }
}
