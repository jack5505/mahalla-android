package uz.mahalla.feature.pharmacy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик нового товара витрины (issue #252): обязательны только имя и
 * цена, остальное схема не ограничивает.
 */
class NewPharmacyProductDraftTest {

    @Test
    fun `an empty draft cannot be submitted`() {
        assertFalse(NewPharmacyProductDraft().canSubmit)
    }

    @Test
    fun `name is required and limited to 300 characters, as the schema says`() {
        val blank = NewPharmacyProductDraft(name = "   ", priceText = "1000")
        assertFalse(blank.isNameValid)

        val tooLong = NewPharmacyProductDraft(name = "a".repeat(301), priceText = "1000")
        assertFalse(tooLong.isNameValid)

        val atLimit = NewPharmacyProductDraft(name = "a".repeat(300), priceText = "1000")
        assertTrue(atLimit.isNameValid)
        assertTrue(atLimit.canSubmit)
    }

    @Test
    fun `garbage in the price field is an error, not a silent zero`() {
        assertNull(NewPharmacyProductDraft(priceText = "abc").priceSum)
        assertNull(NewPharmacyProductDraft(priceText = "").priceSum)
        assertEquals(0L, NewPharmacyProductDraft(priceText = "0").priceSum)
        assertEquals(12_000L, NewPharmacyProductDraft(priceText = "12000").priceSum)
    }

    @Test
    fun `a negative price parses but is not a valid one, as the schema minimum says`() {
        val draft = NewPharmacyProductDraft(name = "A", priceText = "-1")
        assertEquals(-1L, draft.priceSum)
        assertFalse(draft.isPriceValid)
        assertFalse(draft.canSubmit)
    }

    @Test
    fun `a free item is a valid price, same as the schema minimum`() {
        val draft = NewPharmacyProductDraft(name = "Bepul namuna", priceText = "0")
        assertTrue(draft.isPriceValid)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `stock is optional, but garbage in it does not silently become empty`() {
        val blank = NewPharmacyProductDraft(name = "A", priceText = "1000", stockText = "")
        assertTrue(blank.isStockValid)
        assertNull(blank.stockQuantity)

        val garbage = NewPharmacyProductDraft(name = "A", priceText = "1000", stockText = "abc")
        assertFalse(garbage.isStockValid)
        assertFalse(garbage.canSubmit)

        val negative = NewPharmacyProductDraft(name = "A", priceText = "1000", stockText = "-3")
        assertFalse(negative.isStockValid)

        val valid = NewPharmacyProductDraft(name = "A", priceText = "1000", stockText = "5")
        assertTrue(valid.isStockValid)
        assertEquals(5, valid.stockQuantity)
        assertTrue(valid.canSubmit)
    }

    @Test
    fun `with-copies change one field and keep the rest`() {
        val draft = NewPharmacyProductDraft()
            .withName("Paratsetamol")
            .withManufacturer("Uzpharm")
            .withDosageForm("tabletka")
            .withStrength("500 mg")
            .withDescription("Isitma tushiradi")
            .withPrice("12000")
            .withStock("10")
            .withPrescription(true)

        assertEquals("Paratsetamol", draft.name)
        assertEquals("Uzpharm", draft.manufacturer)
        assertEquals("tabletka", draft.dosageForm)
        assertEquals("500 mg", draft.strength)
        assertEquals("Isitma tushiradi", draft.description)
        assertEquals(12_000L, draft.priceSum)
        assertEquals(10, draft.stockQuantity)
        assertTrue(draft.requiresPrescription)
        assertTrue(draft.canSubmit)
    }
}
