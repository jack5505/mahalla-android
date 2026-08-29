package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.core.result.ApiError

/** Промокоды (эпик 5.2): расчёт скидки и разбор отказов сервера. */
class PromoCodeTest {

    @Test
    fun `fixed promo subtracts its value`() {
        val promo = PromoCode("FIX", PromoKind.Fixed, value = 10_000)

        assertEquals(10_000L, promo.discountFor(50_000))
    }

    @Test
    fun `percent promo is rounded down`() {
        val promo = PromoCode("TEN", PromoKind.Percent, value = 10)

        assertEquals(999L, promo.discountFor(9_999))
    }

    @Test
    fun `percent promo respects its cap`() {
        val promo = PromoCode("TEN", PromoKind.Percent, value = 50, maxDiscountSum = 20_000)

        assertEquals(20_000L, promo.discountFor(100_000))
    }

    @Test
    fun `promo below the minimum order gives nothing`() {
        val promo = PromoCode("BIG", PromoKind.Fixed, value = 20_000, minOrderSum = 100_000)

        assertEquals(0L, promo.discountFor(99_999))
        assertEquals(20_000L, promo.discountFor(100_000))
    }

    @Test
    fun `discount never exceeds the order`() {
        val promo = PromoCode("HUGE", PromoKind.Fixed, value = 500_000)

        assertEquals(30_000L, promo.discountFor(30_000))
    }

    @Test
    fun `a percent above one hundred is clamped`() {
        // Сервер прислал 150 % — это ошибка данных, а не бесплатный обед плюс
        // доплата клиенту.
        val promo = PromoCode("BUG", PromoKind.Percent, value = 150)

        assertEquals(50_000L, promo.discountFor(50_000))
    }

    @Test
    fun `http codes map to distinct reasons`() {
        assertEquals(PromoFailure.NotFound, ApiError.NotFound.asPromoFailure())
        assertEquals(PromoFailure.Expired, ApiError.Http(410, null).asPromoFailure())
        assertEquals(
            PromoFailure.MinOrder(100_000),
            ApiError.Http(409, null).asPromoFailure(minOrderSum = 100_000),
        )
    }

    @Test
    fun `a network problem is not blamed on the code`() {
        // Иначе человек начнёт переписывать правильные буквы.
        assertEquals(PromoFailure.Network, ApiError.NoConnection.asPromoFailure())
        assertEquals(PromoFailure.Network, ApiError.Http(500, null).asPromoFailure())
    }
}
