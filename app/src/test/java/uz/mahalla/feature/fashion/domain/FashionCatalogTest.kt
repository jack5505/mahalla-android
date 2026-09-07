package uz.mahalla.feature.fashion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Домен каталога одежды (issue #108): цена, скидка, пол и конец списка.
 *
 * Всё, что связано с деньгами, обязано проверяться без Android: ошибка здесь
 * стоит человеку не той суммы на витрине.
 */
class FashionCatalogTest {

    @Test
    fun `sale price is used only when it is lower than the base one`() {
        val discounted = product(basePriceSum = 320_000, salePriceSum = 240_000)
        assertEquals(240_000L, discounted.priceSum)
        assertTrue(discounted.hasDiscount)

        // Ноль в `salePrice` у бэкенда означает «акции нет», а не «бесплатно».
        val noSale = product(basePriceSum = 320_000, salePriceSum = 0)
        assertEquals(320_000L, noSale.priceSum)
        assertFalse(noSale.hasDiscount)

        // «Скидка» дороже обычной цены — ошибка данных, из-за которой человек
        // заплатил бы больше.
        val broken = product(basePriceSum = 320_000, salePriceSum = 400_000)
        assertEquals(320_000L, broken.priceSum)
        assertFalse(broken.hasDiscount)

        val absent = product(basePriceSum = 320_000, salePriceSum = null)
        assertEquals(320_000L, absent.priceSum)
    }

    @Test
    fun `gender falls back to unknown instead of unisex`() {
        assertEquals(ProductGender.Male, ProductGender.fromApi("MALE"))
        assertEquals(ProductGender.Female, ProductGender.fromApi(" female "))
        assertEquals(ProductGender.Kids, ProductGender.fromApi("kids"))

        // Назвать унисексом то, что им не является, значит соврать о товаре.
        assertEquals(ProductGender.Unknown, ProductGender.fromApi(null))
        assertEquals(ProductGender.Unknown, ProductGender.fromApi(""))
        assertEquals(ProductGender.Unknown, ProductGender.fromApi("TEEN"))
    }

    @Test
    fun `catalog stops paging when the server stays silent about pages`() {
        assertTrue(FashionCatalogPage(page = 0, totalPages = 3).hasMore)
        assertFalse(FashionCatalogPage(page = 2, totalPages = 3).hasMore)
        assertFalse(FashionCatalogPage(page = 0, totalPages = 1).hasMore)
        assertFalse(FashionCatalogPage(page = 0, totalPages = 0).hasMore)

        // Молчание о страницах останавливает догрузку: лучше не показать
        // хвост, чем крутить одну страницу в цикле.
        assertFalse(FashionCatalogPage(page = 0, totalPages = null).hasMore)
    }

    private fun product(basePriceSum: Long, salePriceSum: Long?) = FashionProduct(
        id = "p-1",
        storeId = "s-1",
        name = "Ko'ylak",
        basePriceSum = basePriceSum,
        salePriceSum = salePriceSum,
    )
}
