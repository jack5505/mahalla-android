package uz.mahalla.feature.pharmacy.data

import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
import uz.mahalla.feature.pharmacy.domain.PharmacyProductPage
import uz.mahalla.feature.pharmacy.domain.ProductStock

/**
 * Разбор мягкий, как в каталоге (issue #53): битое поле не роняет витрину.
 *
 * Товар **без `id`** отбрасывается: в `LazyColumn` это дубликат ключа и
 * падение списка. Товар без имени — тоже: строка, у которой нечего прочитать,
 * на витрине бесполезна, а «Без названия» рядом с ценой читается как ошибка
 * приложения, а не как ответ сервера.
 *
 * Всё остальное товар не прячет: нет цены — не показываем цену, нет флага
 * наличия — [ProductStock.Unknown], а не выдуманное «есть».
 */
internal fun ProductDto.toDomain(): PharmacyProduct? {
    val productId = id?.takeIf { it.isNotBlank() } ?: return null
    val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Отрицательный остаток — не «минус три упаковки», а мусор; при этом
    // ноль значащий, он означает «кончилось».
    val stockQuantity = this.stockQuantity?.takeIf { it >= 0 }
    val availability = isAvailable ?: available
    return PharmacyProduct(
        id = productId,
        name = title,
        manufacturer = manufacturer?.trim()?.takeIf { it.isNotEmpty() },
        dosageForm = dosageForm?.trim()?.takeIf { it.isNotEmpty() },
        strength = strength?.trim()?.takeIf { it.isNotEmpty() },
        // Отрицательная цена — тоже мусор; ноль оставляем: бесплатное
        // приложение к рецепту вполне бывает.
        priceSum = price?.takeIf { it >= 0 },
        stockQuantity = stockQuantity,
        stock = ProductStock.of(isAvailable = availability, stockQuantity = stockQuantity),
        requiresPrescription = requiresPrescription ?: prescriptionRequired ?: false,
    )
}

/**
 * Страница витрины.
 *
 * [PharmacyProductPage.hasMore] — по `last`, при его отсутствии по
 * `page`/`totalPages`. Полное молчание о страницах догрузку останавливает:
 * лучше не показать хвост, чем крутить одну и ту же страницу в цикле.
 */
internal fun ProductPageDto.toDomain(): PharmacyProductPage = PharmacyProductPage(
    items = content.mapNotNull(ProductDto::toDomain),
    hasMore = when {
        last != null -> !last
        page != null && totalPages != null -> page + 1 < totalPages
        else -> false
    },
)
