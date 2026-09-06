package uz.mahalla.feature.fashion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор варианта товара (issue #108).
 *
 * Ошибка в этих правилах стоит человеку не того размера в посылке, а
 * скриншотом такое не ловится — поэтому правила чистые и проверяются здесь.
 */
class VariantSelectionTest {

    @Test
    fun `stock is only trusted when the server counts it`() {
        // Молчание сервера об остатке — «есть»: спрятать товар из продажи по
        // отсутствующему полю значит закрыть магазин целиком.
        assertTrue(variant("v-1", "Oq", "M").isOrderable)
        assertTrue(variant("v-1", "Oq", "M", stock = 3).isOrderable)
        assertFalse(variant("v-1", "Oq", "M", stock = 0).isOrderable)
        assertFalse(variant("v-1", "Oq", "M", available = false).isOrderable)
    }

    @Test
    fun `first orderable variant is preselected`() {
        val detail = detail(
            variant("v-1", "Oq", "S", stock = 0),
            variant("v-2", "Oq", "M"),
            variant("v-3", "Qora", "M"),
        )

        assertEquals("v-2", VariantSelection.initial(detail)?.id)
    }

    @Test
    fun `product without a single orderable variant still shows something`() {
        // Пустая карточка объясняет меньше: товар виден, но кнопка выключена.
        val detail = detail(
            variant("v-1", "Oq", "S", stock = 0),
            variant("v-2", "Oq", "M", available = false),
        )

        assertEquals("v-1", VariantSelection.initial(detail)?.id)
        assertFalse(detail.hasOrderableVariant)
    }

    @Test
    fun `variantless product has nothing to preselect`() {
        assertNull(VariantSelection.initial(detail()))
    }

    @Test
    fun `changing the colour keeps the size the person already picked`() {
        val detail = detail(
            variant("v-1", "Oq", "S"),
            variant("v-2", "Oq", "L"),
            variant("v-3", "Qora", "S"),
            variant("v-4", "Qora", "L"),
        )
        val current = detail.variant("v-2")

        // Размер выбирают один раз и дальше листают цвета: сброс на первый
        // размер — способ уехать не в своём.
        assertEquals("v-4", VariantSelection.selectColor(detail, "Qora", current)?.id)
    }

    @Test
    fun `missing size in the new colour falls back to an orderable one`() {
        val detail = detail(
            variant("v-1", "Oq", "XL"),
            variant("v-2", "Qora", "S", stock = 0),
            variant("v-3", "Qora", "M"),
        )
        val current = detail.variant("v-1")

        assertEquals("v-3", VariantSelection.selectColor(detail, "Qora", current)?.id)
    }

    @Test
    fun `unknown colour and unknown variant change nothing`() {
        val detail = detail(variant("v-1", "Oq", "M"))
        val current = detail.variant("v-1")

        // Такое приезжает с уже сменившейся карточки — менять выбор по чужому
        // id нельзя.
        assertEquals("v-1", VariantSelection.selectColor(detail, "Yashil", current)?.id)
        assertEquals("v-1", VariantSelection.selectVariant(detail, "v-99", current)?.id)
    }

    @Test
    fun `colours keep the order the store entered them in`() {
        val detail = detail(
            variant("v-1", "Qora", "M"),
            variant("v-2", "Oq", "M"),
            variant("v-3", "Qora", "L"),
        )

        assertEquals(listOf("Qora", "Oq"), detail.colors)
        assertEquals(listOf("v-1", "v-3"), detail.variantsOf("Qora").map(ProductVariant::id))
    }

    @Test
    fun `price of the chosen variant wins over the product price`() {
        val detail = detail(
            variant("v-1", "Oq", "M", price = 0),
            variant("v-2", "Oq", "XXL", price = 300_000),
        ).copy(basePriceSum = 240_000, salePriceSum = 200_000)

        // XXL дороже — показывать общую цену там, где платят другую, нельзя.
        assertEquals(300_000L, detail.priceOf(detail.variant("v-2")))
        // У варианта цены нет — берётся акционная цена товара.
        assertEquals(200_000L, detail.priceOf(detail.variant("v-1")))
        assertEquals(200_000L, detail.priceOf(null))
    }

    private fun detail(vararg variants: ProductVariant) = FashionProductDetail(
        id = "p-1",
        storeId = "s-1",
        name = "Ko'ylak",
        variants = variants.toList(),
    )

    private fun variant(
        id: String,
        color: String,
        size: String,
        stock: Int? = null,
        available: Boolean = true,
        price: Long = 240_000,
    ) = ProductVariant(
        id = id,
        colorName = color,
        size = size,
        priceSum = price,
        stockQuantity = stock,
        isAvailable = available,
    )
}
