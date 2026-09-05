package uz.mahalla.feature.fashion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Серверная корзина одежды (issue #108).
 *
 * Главное правило вертикали: корзина общая на все магазины, а заказ уходит по
 * одному `placeId` — значит корзина обязана делиться по магазинам, иначе
 * оформление собрало бы чужие вещи в один заказ.
 */
class FashionCartTest {

    @Test
    fun `cart is split by store in the order the lines arrived`() {
        val cart = FashionCart(
            listOf(
                item("v-1", store = "s-1", price = 100_000, quantity = 2),
                item("v-2", store = "s-2", price = 50_000),
                item("v-3", store = "s-1", price = 30_000),
            ),
        )

        assertEquals(listOf("s-1", "s-2"), cart.stores.map(FashionCartStore::storeId))
        assertEquals(
            listOf("v-1", "v-3"),
            cart.store("s-1")?.items?.map(FashionCartItem::variantId),
        )
        assertEquals(230_000L, cart.store("s-1")?.totalSum)
        assertEquals(50_000L, cart.store("s-2")?.totalSum)
        assertEquals(280_000L, cart.totalSum)
        assertEquals(4, cart.itemCount)
        assertNull(cart.store("s-3"))
    }

    @Test
    fun `line total comes from the server when it named one`() {
        // Сумму строки считает сервер: у него могут быть свои акции «3 по
        // цене 2», и пересчитывать её на клиенте значит разойтись с чеком.
        val fromServer = item("v-1", price = 100_000, quantity = 3, serverTotal = 250_000)
        assertEquals(250_000L, fromServer.totalSum)

        // Не назвал — считаем сами: показать ноль значит показать бесплатную
        // покупку.
        val computed = item("v-1", price = 100_000, quantity = 3)
        assertEquals(300_000L, computed.totalSum)
    }

    @Test
    fun `variant label joins only what the server actually sent`() {
        assertEquals("Qora · L", item("v-1", color = "Qora", size = "L").variantLabel)
        assertEquals("L", item("v-1", color = null, size = "L").variantLabel)
        assertEquals("", item("v-1", color = null, size = null).variantLabel)
    }

    @Test
    fun `quantity is clamped and zero means removal`() {
        assertEquals(1, FashionCartRules.normalize(1))
        assertEquals(99, FashionCartRules.normalize(FashionCartRules.MAX_QUANTITY + 5))
        // Ноль серверу не отправляется никогда: у удаления своя ручка, а что
        // сделает бэкенд с `quantity=0`, из контракта не следует.
        assertEquals(1, FashionCartRules.normalize(0))

        assertTrue(FashionCartRules.isRemoval(0))
        assertTrue(FashionCartRules.isRemoval(-1))
        assertFalse(FashionCartRules.isRemoval(1))
    }

    @Test
    fun `empty cart knows it is empty`() {
        assertTrue(FashionCart().isEmpty)
        assertEquals(0, FashionCart().itemCount)
        assertEquals(0L, FashionCart().totalSum)
    }

    private fun item(
        variantId: String,
        store: String = "s-1",
        price: Long = 100_000,
        quantity: Int = 1,
        serverTotal: Long? = null,
        color: String? = "Oq",
        size: String? = "M",
    ) = FashionCartItem(
        variantId = variantId,
        storeId = store,
        productName = "Ko'ylak",
        colorName = color,
        size = size,
        unitPriceSum = price,
        quantity = quantity,
        serverTotalSum = serverTotal,
    )
}
