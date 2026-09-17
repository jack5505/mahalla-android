package uz.mahalla.feature.promotions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик новой акции заведения (issue #252): обязателен только заголовок,
 * а вид акции требует своего числа — процент для `PercentOff`, сумма для
 * `FixedOff`, `FreeDelivery` своего числа не требует.
 */
class NewPromotionDraftTest {

    @Test
    fun `an empty draft cannot be submitted`() {
        assertFalse(NewPromotionDraft().canSubmit)
    }

    @Test
    fun `a blank title is invalid, a real one is not`() {
        assertFalse(NewPromotionDraft(title = "   ").isTitleValid)
        assertTrue(NewPromotionDraft(title = "20% chegirma").isTitleValid)
    }

    @Test
    fun `percent off requires a percent between 1 and 100`() {
        val noPercent = NewPromotionDraft(title = "A", type = CreatablePromoType.PercentOff)
        assertFalse(noPercent.isDiscountValid)

        val zero = noPercent.copy(discountPercentText = "0")
        assertFalse(zero.isDiscountValid)

        val tooBig = noPercent.copy(discountPercentText = "150")
        assertFalse(tooBig.isDiscountValid)

        val valid = noPercent.copy(discountPercentText = "20")
        assertTrue(valid.isDiscountValid)
        assertTrue(valid.canSubmit)
    }

    @Test
    fun `fixed off requires a positive amount`() {
        val noAmount = NewPromotionDraft(title = "A", type = CreatablePromoType.FixedOff)
        assertFalse(noAmount.isDiscountValid)

        val zero = noAmount.copy(discountAmountText = "0")
        assertFalse(zero.isDiscountValid)

        val valid = noAmount.copy(discountAmountText = "50000")
        assertTrue(valid.isDiscountValid)
        assertTrue(valid.canSubmit)
    }

    @Test
    fun `free delivery needs no number of its own`() {
        val draft = NewPromotionDraft(title = "Bepul yetkazib berish", type = CreatablePromoType.FreeDelivery)
        assertTrue(draft.isDiscountValid)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `digits are picked out of the string, same as the pharmacy price`() {
        val draft = NewPromotionDraft(discountAmountText = "50 000", minOrderAmountText = "100,000 so'm")
        assertEquals(50_000L, draft.discountAmountSum)
        assertEquals(100_000L, draft.minOrderAmountSum)
    }

    @Test
    fun `no digits at all is null, not a silent zero`() {
        assertNull(NewPromotionDraft(discountAmountText = "abc").discountAmountSum)
        assertNull(NewPromotionDraft(discountAmountText = "").discountAmountSum)
    }

    @Test
    fun `with-copies change one field and keep the rest`() {
        val draft = NewPromotionDraft()
            .withTitle("20% chegirma")
            .withDescription("Faqat ish kunlari")
            .withType(CreatablePromoType.PercentOff)
            .withDiscountPercent("20")
            .withMinOrderAmount("50000")
            .withPromoCode("OSH20")

        assertEquals("20% chegirma", draft.title)
        assertEquals("Faqat ish kunlari", draft.description)
        assertEquals(CreatablePromoType.PercentOff, draft.type)
        assertEquals(20, draft.discountPercent)
        assertEquals(50_000L, draft.minOrderAmountSum)
        assertEquals("OSH20", draft.promoCode)
        assertTrue(draft.canSubmit)
    }
}
