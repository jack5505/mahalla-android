package uz.mahalla.feature.pharmacy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила витрины аптеки (issue #100): наличие и подписи карточки.
 *
 * Наличие — то единственное, ради чего этот экран открывают, поэтому его
 * разбор проверяется на всех сочетаниях двух необязательных полей контракта.
 */
class PharmacyProductTest {

    @Test
    fun `a positive flag or a positive remainder means the product is on the shelf`() {
        assertEquals(ProductStock.InStock, ProductStock.of(isAvailable = true, stockQuantity = null))
        assertEquals(ProductStock.InStock, ProductStock.of(isAvailable = null, stockQuantity = 3))
        assertEquals(ProductStock.InStock, ProductStock.of(isAvailable = true, stockQuantity = 3))
    }

    @Test
    fun `a negative flag wins over a positive remainder`() {
        // Витрина и склад расходятся — дело обычное; ошибиться здесь значит
        // отправить человека через полгорода зря.
        assertEquals(
            ProductStock.OutOfStock,
            ProductStock.of(isAvailable = false, stockQuantity = 7),
        )
    }

    @Test
    fun `an empty shelf wins over a positive flag`() {
        assertEquals(
            ProductStock.OutOfStock,
            ProductStock.of(isAvailable = true, stockQuantity = 0),
        )
        assertEquals(
            ProductStock.OutOfStock,
            ProductStock.of(isAvailable = null, stockQuantity = 0),
        )
    }

    @Test
    fun `silence about availability is not a promise of availability`() {
        // «Неизвестно» честнее выдуманного «есть»: у бэкенда оба поля
        // необязательны, и молчание про наличие встречается.
        assertEquals(
            ProductStock.Unknown,
            ProductStock.of(isAvailable = null, stockQuantity = null),
        )
    }

    @Test
    fun `the remainder is named only while it is running out`() {
        assertTrue(product(stockQuantity = 1).showsStockQuantity)
        assertTrue(
            product(stockQuantity = PharmacyProduct.LOW_STOCK_THRESHOLD).showsStockQuantity,
        )
        // «Осталось 340» — складская сводка, а не повод поспешить.
        assertFalse(
            product(stockQuantity = PharmacyProduct.LOW_STOCK_THRESHOLD + 1).showsStockQuantity,
        )
        assertFalse(product(stockQuantity = null).showsStockQuantity)
    }

    @Test
    fun `the remainder of a product that is gone is not shown at all`() {
        // Ноль уже сказан словами «нет в наличии»: «осталось 0 упаковок»
        // рядом с этим читается как ошибка приложения.
        assertFalse(
            product(stockQuantity = 0, stock = ProductStock.OutOfStock).showsStockQuantity,
        )
        assertFalse(
            product(stockQuantity = 2, stock = ProductStock.Unknown).showsStockQuantity,
        )
    }

    @Test
    fun `dosage form and strength read as one caption`() {
        assertEquals(
            "tabletka, 500 mg",
            product(dosageForm = "tabletka", strength = "500 mg").formLabel,
        )
        assertEquals("tabletka", product(dosageForm = "tabletka").formLabel)
        assertEquals("500 mg", product(strength = "500 mg").formLabel)
        assertNull(product().formLabel)
    }

    private fun product(
        dosageForm: String? = null,
        strength: String? = null,
        stockQuantity: Int? = null,
        stock: ProductStock = ProductStock.InStock,
    ) = PharmacyProduct(
        id = "p-1",
        name = "Paratsetamol",
        dosageForm = dosageForm,
        strength = strength,
        stockQuantity = stockQuantity,
        stock = stock,
    )
}
