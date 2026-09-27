package uz.mahalla.feature.fashion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик нового варианта — размер/цвет (issue #280): без цвета, размера и
 * цены строку каталога нечем отличить от соседней и нечем продать.
 */
class NewFashionVariantDraftTest {

    @Test
    fun `an empty draft cannot be submitted`() {
        assertFalse(NewFashionVariantDraft().canSubmit)
    }

    @Test
    fun `color and size are required`() {
        val draft = NewFashionVariantDraft(priceText = "240000")
        assertFalse(draft.isColorValid)
        assertFalse(draft.isSizeValid)
        assertFalse(draft.canSubmit)

        val complete = draft.withColorName("Oq").withSize("M")
        assertTrue(complete.isColorValid)
        assertTrue(complete.isSizeValid)
        assertTrue(complete.canSubmit)
    }

    @Test
    fun `no digits at all is an error, not a silent zero`() {
        assertNull(NewFashionVariantDraft(priceText = "abc").priceSum)
        assertNull(NewFashionVariantDraft(priceText = "").priceSum)
        assertEquals(0L, NewFashionVariantDraft(priceText = "0").priceSum)
        assertEquals(240_000L, NewFashionVariantDraft(priceText = "240000").priceSum)
    }

    @Test
    fun `a free variant is a valid price, same as the schema minimum`() {
        val draft = NewFashionVariantDraft(colorName = "Oq", size = "M", priceText = "0")
        assertTrue(draft.isPriceValid)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `stock is optional, but garbage in it does not silently become empty`() {
        val base = NewFashionVariantDraft(colorName = "Oq", size = "M", priceText = "240000")

        val blank = base.withStock("")
        assertTrue(blank.isStockValid)
        assertNull(blank.stockQuantity)

        val garbage = base.withStock("abc")
        assertFalse(garbage.isStockValid)
        assertFalse(garbage.canSubmit)

        val valid = base.withStock("5")
        assertTrue(valid.isStockValid)
        assertEquals(5, valid.stockQuantity)
        assertTrue(valid.canSubmit)
    }

    @Test
    fun `with-copies change one field and keep the rest`() {
        val draft = NewFashionVariantDraft()
            .withColorName("Oq")
            .withColorHex("#FFFFFF")
            .withSize("M")
            .withSku("SKU-1")
            .withPrice("240000")
            .withStock("10")

        assertEquals("Oq", draft.colorName)
        assertEquals("#FFFFFF", draft.colorHex)
        assertEquals("M", draft.size)
        assertEquals("SKU-1", draft.sku)
        assertEquals(240_000L, draft.priceSum)
        assertEquals(10, draft.stockQuantity)
        assertTrue(draft.canSubmit)
    }
}
