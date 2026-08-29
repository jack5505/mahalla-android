package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import uz.mahalla.testutil.cartLine

/**
 * Расчёт корзины (эпик 5.2): количество, модификаторы, промокод, округление.
 *
 * Всё, что связано с деньгами, живёт здесь одним набором чистых функций —
 * ViewModel их не дублирует, поэтому расхождения «на экране одно, в чеке
 * другое» быть не может.
 */
class CartCalculatorTest {

    @Test
    fun `line id distinguishes the same dish with different options`() {
        val plain = CartCalculator.lineId("osh", emptySet())
        val withCheese = CartCalculator.lineId("osh", setOf("cheese"))

        assertNotEquals(plain, withCheese)
        assertEquals("osh", plain)
    }

    @Test
    fun `line id does not depend on the order of the options`() {
        // Множество порядок не гарантирует, а ключ обязан совпасть у двух
        // одинаковых добавлений подряд — иначе появится вторая такая же строка.
        assertEquals(
            CartCalculator.lineId("osh", setOf("cheese", "egg")),
            CartCalculator.lineId("osh", setOf("egg", "cheese")),
        )
    }

    @Test
    fun `adding the same line increases the quantity instead of duplicating`() {
        val lines = CartCalculator.add(
            CartCalculator.add(emptyList(), cartLine("osh")),
            cartLine("osh"),
        )

        assertEquals(1, lines.size)
        assertEquals(2, lines.single().quantity)
    }

    @Test
    fun `the same dish with different options makes two lines`() {
        val lines = CartCalculator.add(
            CartCalculator.add(emptyList(), cartLine("osh")),
            cartLine("osh", optionIds = setOf("cheese"), unitPriceSum = 35_000),
        )

        assertEquals(2, lines.size)
    }

    @Test
    fun `quantity never exceeds the cap`() {
        val lines = CartCalculator.setQuantity(
            listOf(cartLine("osh")),
            CartCalculator.lineId("osh", emptySet()),
            quantity = 500,
        )

        assertEquals(CartCalculator.MAX_QUANTITY, lines.single().quantity)
    }

    @Test
    fun `zero quantity removes the line`() {
        val lines = CartCalculator.setQuantity(
            listOf(cartLine("osh")),
            CartCalculator.lineId("osh", emptySet()),
            quantity = 0,
        )

        assertEquals(emptyList<CartLine>(), lines)
    }

    @Test
    fun `subtotal multiplies the unit price with options by the quantity`() {
        val lines = listOf(
            cartLine("osh", unitPriceSum = 35_000, quantity = 2),
            cartLine("cola", unitPriceSum = 8_000, quantity = 3),
        )

        assertEquals(35_000L * 2 + 8_000L * 3, CartCalculator.subtotal(lines))
    }

    @Test
    fun `percent discount is rounded down to whole sums`() {
        // Округление вверх дало бы клиенту лишнюю суму за счёт заведения и
        // разошлось бы с расчётом бэкенда.
        val cart = cart(
            lines = listOf(cartLine("osh", unitPriceSum = 33_333, quantity = 1)),
            promo = PromoCode("TEN", PromoKind.Percent, value = 10),
        )

        assertEquals(3_333L, CartCalculator.totals(cart).discountSum)
    }

    @Test
    fun `discount never exceeds the price of the items`() {
        val cart = cart(
            lines = listOf(cartLine("osh", unitPriceSum = 30_000)),
            promo = PromoCode("BIG", PromoKind.Fixed, value = 50_000),
        )

        val totals = CartCalculator.totals(cart)

        assertEquals(30_000L, totals.discountSum)
        assertEquals(0L, totals.totalSum)
    }

    @Test
    fun `delivery is added on top and is not discounted`() {
        val cart = cart(
            lines = listOf(cartLine("osh", unitPriceSum = 100_000)),
            promo = PromoCode("HALF", PromoKind.Percent, value = 50),
        )

        val totals = CartCalculator.totals(cart, deliverySum = 15_000)

        assertEquals(50_000L, totals.discountSum)
        assertEquals(65_000L, totals.totalSum)
    }

    @Test
    fun `a promo below its minimum order gives no discount`() {
        val cart = cart(
            lines = listOf(cartLine("osh", unitPriceSum = 30_000)),
            promo = PromoCode("BIG", PromoKind.Fixed, value = 10_000, minOrderSum = 100_000),
        )

        assertEquals(0L, CartCalculator.totals(cart).discountSum)
    }

    @Test
    fun `add never removes a line, whatever the quantity of the existing one`() {
        // «Добавить», которое из-за нулевого количества удаляет строку, —
        // ловушка: у add и setQuantity разные смыслы.
        val broken = cartLine("osh").copy(quantity = 0)

        val lines = CartCalculator.add(listOf(broken), cartLine("osh"))

        assertEquals(1, lines.size)
        assertEquals(1, lines.single().quantity)
    }

    @Test
    fun `add clamps the quantity of a new line to at least one`() {
        val lines = CartCalculator.add(emptyList(), cartLine("osh", quantity = 0))

        assertEquals(1, lines.single().quantity)
    }

    @Test
    fun `item count sums the quantities, not the lines`() {
        val cart = cart(
            lines = listOf(
                cartLine("osh", quantity = 2),
                cartLine("cola", quantity = 3),
            ),
        )

        assertEquals(5, cart.itemCount)
    }

    private fun cart(lines: List<CartLine>, promo: PromoCode? = null) = Cart(
        placeId = "place-1",
        placeName = "Osh markazi",
        lines = lines,
        promo = promo,
    )
}
