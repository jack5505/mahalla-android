package uz.mahalla.feature.fashion.domain

import java.util.Locale

/**
 * Категория одежды (issue #108). Справочник **общий**, а не свой у каждого
 * магазина: `GET fashion/categories` не принимает ни одного параметра.
 *
 * Иконка приходит ссылкой ([iconUrl]) и пока не показывается — загрузчика
 * изображений в проекте нет (#60). Поле оставлено в домене намеренно: в
 * отличие от `imageUrl` товара, оно нужно самому списку фильтров, и с
 * появлением `MahallaAsyncImage` его подключение — одна строка на экране.
 */
data class FashionCategory(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
)

/**
 * Кому вещь. Значения — перечисление бэкенда (`MALE`, `FEMALE`, `UNISEX`,
 * `KIDS`).
 *
 * [Unknown] обязателен и означает «сервер не сказал», а не «унисекс»: назвать
 * унисексом то, что им не является, — соврать о товаре, за который платят.
 */
enum class ProductGender {
    Male,
    Female,
    Unisex,
    Kids,
    Unknown,
    ;

    val apiValue: String
        get() = when (this) {
            Male -> "MALE"
            Female -> "FEMALE"
            Unisex -> "UNISEX"
            Kids -> "KIDS"
            Unknown -> ""
        }

    companion object {
        fun fromApi(value: String?): ProductGender {
            val normalized = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it != Unknown && it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Товар в выдаче каталога (`ProductSummary` бэкенда).
 *
 * Цен две: [basePriceSum] — обычная, [salePriceSum] — по акции. Что показывать
 * человеку, решает [priceSum]: скидка учитывается только если она и вправду
 * скидка (см. его KDoc).
 *
 * Фотографии здесь нет вовсе — в `ProductSummary` её не отдаёт и сам бэкенд
 * (изображения живут у вариантов, `VariantResponse.images`).
 */
data class FashionProduct(
    val id: String,
    val storeId: String,
    val name: String,
    val brand: String? = null,
    val gender: ProductGender = ProductGender.Unknown,
    val basePriceSum: Long = 0,
    val salePriceSum: Long? = null,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val isNew: Boolean = false,
    val isBestseller: Boolean = false,
) {
    /**
     * Цена, которую платят.
     *
     * Акционная берётся, только если она положительна **и** ниже обычной: ноль
     * в этом поле у бэкенда означает «акции нет», а «скидка» дороже обычной
     * цены — ошибка данных, из-за которой человек заплатил бы больше.
     */
    val priceSum: Long
        get() = salePriceSum?.takeIf { it > 0 && it < basePriceSum } ?: basePriceSum

    /** Есть ли что зачёркивать рядом с ценой. */
    val hasDiscount: Boolean get() = priceSum < basePriceSum
}

/**
 * Страница каталога (`CatalogResponse`).
 *
 * Флага `last` здесь, в отличие от остальных страничных ответов бэкенда, нет —
 * конец списка считается по [page] и `totalPages`. Полное молчание сервера о
 * страницах останавливает догрузку: лучше не показать хвост, чем крутить одну
 * и ту же страницу в цикле.
 */
data class FashionCatalogPage(
    val items: List<FashionProduct> = emptyList(),
    val page: Int = 0,
    val totalPages: Int? = null,
    val totalElements: Long? = null,
) {
    val hasMore: Boolean
        get() {
            val total = totalPages ?: return false
            return page + 1 < total
        }
}
