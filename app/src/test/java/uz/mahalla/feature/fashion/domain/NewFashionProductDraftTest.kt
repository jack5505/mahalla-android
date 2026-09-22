package uz.mahalla.feature.fashion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик нового товара витрины (issue #280): обязательны только имя и
 * цена, остальное схема не ограничивает.
 */
class NewFashionProductDraftTest {

    @Test
    fun `an empty draft cannot be submitted`() {
        assertFalse(NewFashionProductDraft().canSubmit)
    }

    @Test
    fun `a blank name is invalid`() {
        assertFalse(NewFashionProductDraft(name = "   ", priceText = "1000").isNameValid)
        assertTrue(NewFashionProductDraft(name = "Ko'ylak", priceText = "1000").isNameValid)
    }

    @Test
    fun `no digits at all is an error, not a silent zero`() {
        assertNull(NewFashionProductDraft(priceText = "abc").priceSum)
        assertNull(NewFashionProductDraft(priceText = "").priceSum)
        assertEquals(0L, NewFashionProductDraft(priceText = "0").priceSum)
        assertEquals(320_000L, NewFashionProductDraft(priceText = "320000").priceSum)
    }

    @Test
    fun `digits are picked out of the string, same as the wallet top-up amount`() {
        assertEquals(320_000L, NewFashionProductDraft(priceText = "320 000").priceSum)
    }

    @Test
    fun `a free item is a valid price, same as the schema minimum`() {
        val draft = NewFashionProductDraft(name = "Namuna", priceText = "0")
        assertTrue(draft.isPriceValid)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `a second tap on the same gender clears the selection`() {
        val selected = NewFashionProductDraft().withGender(ProductGender.Female)
        assertEquals(ProductGender.Female, selected.gender)

        val cleared = selected.withGender(ProductGender.Female)
        assertNull(cleared.gender)

        val switched = selected.withGender(ProductGender.Male)
        assertEquals(ProductGender.Male, switched.gender)
    }

    @Test
    fun `a second tap on the same category clears the selection`() {
        val selected = NewFashionProductDraft().withCategory("c-1")
        assertEquals("c-1", selected.categoryId)

        val cleared = selected.withCategory("c-1")
        assertNull(cleared.categoryId)

        val switched = selected.withCategory("c-2")
        assertEquals("c-2", switched.categoryId)
    }

    @Test
    fun `with-copies change one field and keep the rest`() {
        val draft = NewFashionProductDraft()
            .withName("Oq ko'ylak")
            .withDescription("Paxta 100%")
            .withBrand("Mahalla")
            .withMaterial("Paxta")
            .withCareInstructions("30 daraja")
            .withSizeGuide("S-XL")
            .withGender(ProductGender.Unisex)
            .withCategory("c-1")
            .withPrice("320000")

        assertEquals("Oq ko'ylak", draft.name)
        assertEquals("Paxta 100%", draft.description)
        assertEquals("Mahalla", draft.brand)
        assertEquals("Paxta", draft.material)
        assertEquals("30 daraja", draft.careInstructions)
        assertEquals("S-XL", draft.sizeGuide)
        assertEquals(ProductGender.Unisex, draft.gender)
        assertEquals("c-1", draft.categoryId)
        assertEquals(320_000L, draft.priceSum)
        assertTrue(draft.canSubmit)
    }
}
