package uz.mahalla.feature.activity.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.contract.ContractSample
import uz.mahalla.feature.fashion.data.OrderPageDto
import uz.mahalla.feature.food.data.OrderViewDto

/**
 * Сверка общего `GET orders` с живым стендом (issue #148 — единственный из
 * пяти источников «Моих активностей» без `/my`, и ни один путь под токеном
 * не был проверен ни разу: анонимно всё отвечает `401`).
 *
 * Пробы: `contract/orders.sh`. Скоуп по пользователю ("чужие заказы не
 * приходят") этот тест не проверяет — на снятом ответе нечем: нужен второй
 * живой аккаунт, а `CONTRACT_REFRESH_TOKEN` в стенде один. Здесь закрепляются
 * только форма ответа (issue #148 п.2 — список смешивает вертикали) и то, что
 * `orders/{orderId}` отдаёт `OrderView`, а не что-то другое (issue #148 п.3,
 * issue #9).
 *
 * Проба не снята — тест пропускается, а не краснеет: см. `BookingContractTest`.
 */
class OrdersContractTest {

    private val json = NetworkFactory.json()

    private fun sampleOrSkip(name: String): JsonObject {
        val root = ContractSample.load("orders", name)
        assumeTrue("проба «$name» не снята — запусти contract/orders.sh", root != null)
        return root!!
    }

    private fun assertFieldsMatch(
        objects: List<JsonObject>,
        serializer: kotlinx.serialization.KSerializer<*>,
        what: String,
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
            ContractSample.declaredButAbsent(objects, serializer),
        )
    }

    @Test
    fun `orders page without vertical filter matches OrderPageDto`() {
        val root = sampleOrSkip("all")
        val response = json.decodeFromJsonElement<ApiResponse<OrderPageDto>>(root)
        assertTrue("конверт с success=false: ${response.error}", response.success)
        val page = response.data
        assumeTrue("страница заказов пуста — полей сверять не на чем", page?.content?.isNotEmpty() == true)
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]?.let { (it as? JsonObject)?.get("content") }),
            OrderViewDto.serializer(),
            what = "заказы страницы",
        )
    }

    @Test
    fun `order detail via the general endpoint matches OrderView`() {
        val root = sampleOrSkip("detail")
        val response = json.decodeFromJsonElement<ApiResponse<OrderViewDto>>(root)
        assertTrue(
            "стенд отказал на GET orders/{orderId}: ${response.error}",
            response.success,
        )
        assertFieldsMatch(
            ContractSample.objectsIn(root["data"]),
            OrderViewDto.serializer(),
            what = "заказ",
        )
    }
}
