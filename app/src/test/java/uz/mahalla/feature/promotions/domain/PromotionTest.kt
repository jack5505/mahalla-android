package uz.mahalla.feature.promotions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Правила блока акций (issue #104): что показывать и куда это ведёт.
 *
 * Всё чистыми функциями: «акция закончилась вчера, а на экране висит» — не тот
 * дефект, который ловится глазами по превью.
 */
class PromotionTest {

    @Test
    fun `server enum values are parsed, unknown one does not become a discount`() {
        assertEquals(PromoType.PercentOff, PromoType.fromServer("PERCENT_OFF"))
        assertEquals(PromoType.FreeDelivery, PromoType.fromServer("free_delivery"))
        assertEquals(PromoType.FlashSale, PromoType.fromServer(" FLASH_SALE "))
        // Условия за заведение не выдумываем.
        assertEquals(PromoType.Unknown, PromoType.fromServer("MEGA_SALE"))
        assertEquals(PromoType.Unknown, PromoType.fromServer(null))
        assertEquals(PromoType.Unknown, PromoType.fromServer(""))
    }

    @Test
    fun `a promotion without dates is live`() {
        // Молчание сервера о сроке — не повод спрятать акцию.
        assertTrue(promotion().isLiveAt(NOW))
    }

    @Test
    fun `a finished promotion is not shown`() {
        val ended = promotion(endsAt = NOW.minusSeconds(1))

        // Обещание скидки, которой уже нет, хуже пустого блока.
        assertFalse(ended.isLiveAt(NOW))
    }

    @Test
    fun `the end moment is already outside the term`() {
        assertFalse(promotion(endsAt = NOW).isLiveAt(NOW))
        assertTrue(promotion(endsAt = NOW.plusSeconds(1)).isLiveAt(NOW))
    }

    @Test
    fun `a promotion that has not started yet is not shown`() {
        assertFalse(promotion(startsAt = NOW.plusSeconds(60)).isLiveAt(NOW))
        assertTrue(promotion(startsAt = NOW).isLiveAt(NOW))
    }

    @Test
    fun `both words of the server about being off are honoured`() {
        assertFalse(promotion(isActive = false).isLiveAt(NOW))
        assertFalse(promotion(isValid = false).isLiveAt(NOW))
        // А молчание о вердикте акцию не прячет.
        assertTrue(promotion(isValid = null).isLiveAt(NOW))
    }

    @Test
    fun `only a promotion of a known place leads somewhere`() {
        val ofPlace = promotion(placeId = "p-1")

        assertEquals(PromotionTarget.Place("p-1"), PromotionTarget.of(ofPlace))
        assertTrue(ofPlace.isTappable)
    }

    @Test
    fun `a platform promotion stays a text, not a broken tap`() {
        val platform = promotion(placeId = null)

        assertEquals(PromotionTarget.None, PromotionTarget.of(platform))
        assertFalse(platform.isTappable)
        // Пустая строка — то же самое: открывать по ней нечего.
        assertEquals(PromotionTarget.None, PromotionTarget.of(promotion(placeId = " ")))
    }

    @Test
    fun `the home block keeps only live promotions and stays a block`() {
        val promotions = listOf(
            promotion(id = "live-1"),
            promotion(id = "gone", endsAt = NOW.minusSeconds(1)),
            promotion(id = "live-2"),
            promotion(id = "live-3"),
            promotion(id = "live-4"),
            promotion(id = "live-5"),
            promotion(id = "live-6"),
        )

        val home = PromotionFeed.home(promotions, NOW)

        // Ниже блока ещё «рядом» и «рекомендуем» — лентой он быть не должен.
        assertEquals(PromotionFeed.HOME_LIMIT, home.size)
        assertEquals(
            listOf("live-1", "live-2", "live-3", "live-4", "live-5"),
            home.map(Promotion::id),
        )
    }

    @Test
    fun `the server order is kept as is`() {
        val promotions = listOf(promotion(id = "b"), promotion(id = "a"))

        assertEquals(listOf("b", "a"), PromotionFeed.live(promotions, NOW).map(Promotion::id))
    }

    private fun promotion(
        id: String = "promo",
        placeId: String? = null,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
        isActive: Boolean = true,
        isValid: Boolean? = null,
    ) = Promotion(
        id = id,
        title = "Aksiya",
        placeId = placeId,
        startsAt = startsAt,
        endsAt = endsAt,
        isActive = isActive,
        isValid = isValid,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
    }
}
