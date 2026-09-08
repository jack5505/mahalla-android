package uz.mahalla.data.network.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.data.network.NetworkFactory

/**
 * Проверка самого механизма сверки, а не какого-либо контракта: пока пробы со
 * стенда не сняты, `*ContractTest` целиком пропускаются, и ошибка в [ContractSample]
 * осталась бы незамеченной ровно до того дня, когда на неё понадеются.
 *
 * DTO здесь нарочно свой, а не из вертикали: инструмент не должен зависеть от
 * полей, которые завтра поменяются.
 */
class ContractSampleTest {

    @Serializable
    private data class SampleDto(
        @SerialName("id") val id: String? = null,
        @SerialName("title") val title: String? = null,
        @SerialName("isActive") val isActive: Boolean? = null,
    )

    private fun objects(raw: String): List<JsonObject> =
        ContractSample.objectsIn(NetworkFactory.json().parseToJsonElement(raw))

    @Test
    fun `a field the server sends and the DTO ignores is reported`() {
        val payload = objects("""[{"id":"1","title":"Стрижка","discountPrice":5000}]""")
        assertEquals(
            setOf("discountPrice"),
            ContractSample.unknownToClient(payload, SampleDto.serializer()),
        )
    }

    /** Поле, опущенное у одной записи, но пришедшее у другой, лишним не считается. */
    @Test
    fun `keys are pooled across every record in the sample`() {
        val payload = objects("""[{"id":"1"},{"id":"2","title":"Бритьё"}]""")
        assertEquals(
            emptySet<String>(),
            ContractSample.unknownToClient(payload, SampleDto.serializer()),
        )
        assertEquals(
            setOf("isActive"),
            ContractSample.declaredButAbsent(payload, SampleDto.serializer()),
        )
    }

    /** Слоты приезжают массивом строк — сверять поля не на чем, и это не ошибка. */
    @Test
    fun `an array of plain strings yields nothing to compare`() {
        assertEquals(emptyList<JsonObject>(), objects("""["10:00","10:30"]"""))
    }

    @Test
    fun `a single object is compared just like a list`() {
        val payload = objects("""{"id":"1","status":"BOOKED"}""")
        assertEquals(
            setOf("status"),
            ContractSample.unknownToClient(payload, SampleDto.serializer()),
        )
    }

    @Test
    fun `a probe that was never taken reads as absent, not as empty`() {
        assertEquals(null, ContractSample.load("booking", "no-such-probe"))
    }
}
