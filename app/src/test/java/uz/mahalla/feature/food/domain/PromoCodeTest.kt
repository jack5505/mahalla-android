package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ServerError

/**
 * Промокоды (эпик 5.2) под контракт `GET promotions/check` (issue #63): скидку
 * считает сервер, клиент только следит, что она относится к текущей корзине.
 */
class PromoCodeTest {

    @Test
    fun `discount applies to the subtotal it was checked against`() {
        val promo = PromoCode("FIX", discountSum = 10_000, checkedSubtotalSum = 50_000)

        assertEquals(10_000L, promo.discountFor(50_000))
    }

    @Test
    fun `a changed cart drops the discount`() {
        // Сервер считал скидку для 50 000; для другой суммы она уже не его
        // ответ, и показывать её значит назвать число, которого не будет в чеке.
        val promo = PromoCode("FIX", discountSum = 10_000, checkedSubtotalSum = 50_000)

        assertTrue(promo.isStaleFor(60_000))
        assertEquals(0L, promo.discountFor(60_000))
        assertFalse(promo.isStaleFor(50_000))
    }

    @Test
    fun `discount never exceeds the order`() {
        // Сервер прислал скидку больше заказа — это ошибка данных, а не долг
        // заведения перед клиентом.
        val promo = PromoCode("HUGE", discountSum = 500_000, checkedSubtotalSum = 30_000)

        assertEquals(30_000L, promo.discountFor(30_000))
    }

    @Test
    fun `a negative discount is ignored`() {
        val promo = PromoCode("BUG", discountSum = -5_000, checkedSubtotalSum = 30_000)

        assertEquals(0L, promo.discountFor(30_000))
    }

    @Test
    fun `the server error code decides the reason`() {
        // Стенд отвечает `404 NOT_FOUND` с текстом «Promo-kod topilmadi».
        assertEquals(PromoFailure.NotFound, failure(ApiError.NotFound, code = "NOT_FOUND").asPromoFailure())
        assertEquals(
            PromoFailure.Expired,
            failure(ApiError.Http(400, null), code = "PROMO_EXPIRED").asPromoFailure(),
        )
        assertEquals(
            PromoFailure.NotApplicable,
            failure(ApiError.Http(400, null), code = "PROMO_USAGE_LIMIT").asPromoFailure(),
        )
    }

    @Test
    fun `http codes still work without a server code`() {
        assertEquals(PromoFailure.NotFound, failure(ApiError.NotFound).asPromoFailure())
        assertEquals(PromoFailure.Expired, failure(ApiError.Http(410, null)).asPromoFailure())
        assertEquals(PromoFailure.NotApplicable, failure(ApiError.Http(409, null)).asPromoFailure())
    }

    @Test
    fun `a network problem is not blamed on the code`() {
        // Иначе человек начнёт переписывать правильные буквы.
        assertEquals(PromoFailure.Network, failure(ApiError.NoConnection).asPromoFailure())
        assertEquals(PromoFailure.Network, failure(ApiError.Http(500, null)).asPromoFailure())
    }

    private fun failure(error: ApiError, code: String? = null): ApiFailure =
        ApiFailure(error = error, server = code?.let { ServerError(httpCode = 400, code = it) })
}
