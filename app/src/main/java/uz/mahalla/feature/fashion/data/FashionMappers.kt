package uz.mahalla.feature.fashion.data

import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules
import uz.mahalla.feature.fashion.domain.FashionCatalogPage
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProduct
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.ProductGender
import uz.mahalla.feature.fashion.domain.ProductVariant

/**
 * Маппинг вертикали «Одежда» (issue #108).
 *
 * Разбор мягкий, как в остальном каталоге: битое поле не роняет выдачу. А вот
 * запись **без id выбрасывается всегда** — открыть, положить в корзину или
 * отменить её нечем, а в `LazyColumn` одинаковые (пустые) ключи роняют список.
 *
 * Флаги «новинка» и «хит» принимаются под двумя именами: Jackson сериализует
 * `boolean isNew` то как `isNew`, то как `new` (то же правило, что у
 * `isAvailable` в меню и `isRead` в уведомлениях).
 */

fun FashionCategoryDto.toDomain(): FashionCategory? {
    val categoryId = id?.takeIf { it.isNotBlank() } ?: return null
    val label = name?.takeIf { it.isNotBlank() } ?: return null
    return FashionCategory(
        id = categoryId,
        name = label,
        iconUrl = iconUrl?.takeIf(String::isNotBlank),
    )
}

fun CatalogDto.toDomain(): FashionCatalogPage = FashionCatalogPage(
    items = products.mapNotNull(ProductSummaryDto::toDomain),
    page = (page ?: 0).coerceAtLeast(0),
    totalPages = totalPages,
    totalElements = totalElements,
)

fun ProductSummaryDto.toDomain(): FashionProduct? {
    val productId = id?.takeIf { it.isNotBlank() } ?: return null
    return FashionProduct(
        id = productId,
        storeId = storeId.orEmpty(),
        // Товар без названия остаётся в выдаче: подпись ему найдёт экран, а
        // спрятать оплаченный магазином товар из-за пустого поля хуже.
        name = name.orEmpty(),
        brand = brand?.takeIf(String::isNotBlank),
        gender = ProductGender.fromApi(gender),
        // Отрицательная цена — ошибка сервера, а не подарок.
        basePriceSum = (basePrice ?: 0).coerceAtLeast(0),
        salePriceSum = salePrice?.coerceAtLeast(0),
        ratingAvg = ratingAvg ?: 0.0,
        ratingCount = (ratingCount ?: 0).coerceAtLeast(0),
        isNew = isNew ?: new ?: false,
        isBestseller = isBestseller ?: bestseller ?: false,
    )
}

/**
 * Карточка товара. Карта «цвет → варианты» раскладывается плоским списком:
 * имя цвета берётся из ключа карты, потому что у самого варианта поле
 * `colorName` может и не приехать — сервер уже сказал цвет ключом.
 */
fun ProductDetailDto.toDomain(): FashionProductDetail? {
    val productId = id?.takeIf { it.isNotBlank() } ?: return null
    val variants = variantsByColor.flatMap { (color, items) ->
        items.mapNotNull { it.toDomain(fallbackColor = color) }
    }
    return FashionProductDetail(
        id = productId,
        storeId = storeId.orEmpty(),
        name = name.orEmpty(),
        description = description?.takeIf(String::isNotBlank),
        brand = brand?.takeIf(String::isNotBlank),
        material = material?.takeIf(String::isNotBlank),
        careInstructions = careInstructions?.takeIf(String::isNotBlank),
        sizeGuide = sizeGuide?.takeIf(String::isNotBlank),
        gender = ProductGender.fromApi(gender),
        basePriceSum = (basePrice ?: 0).coerceAtLeast(0),
        salePriceSum = salePrice?.coerceAtLeast(0),
        ratingAvg = ratingAvg ?: 0.0,
        ratingCount = (ratingCount ?: 0).coerceAtLeast(0),
        isNew = isNew ?: new ?: false,
        isBestseller = isBestseller ?: bestseller ?: false,
        variants = variants,
    )
}

fun VariantDto.toDomain(fallbackColor: String): ProductVariant? {
    val variantId = id?.takeIf { it.isNotBlank() } ?: return null
    return ProductVariant(
        id = variantId,
        colorName = colorName?.takeIf(String::isNotBlank) ?: fallbackColor,
        size = size?.takeIf(String::isNotBlank).orEmpty(),
        colorHex = colorHex?.takeIf(String::isNotBlank),
        sku = sku?.takeIf(String::isNotBlank),
        priceSum = (price ?: 0).coerceAtLeast(0),
        // Отрицательный остаток — «нет»: единственное осмысленное чтение.
        stockQuantity = stockQuantity?.coerceAtLeast(0),
        isAvailable = isAvailable ?: available ?: true,
    )
}

/**
 * Корзина. Строка без `variantId` выбрасывается: ни изменить, ни удалить, ни
 * заказать её нельзя — а показать как обычную значит обещать заказ, который
 * не соберётся.
 */
fun List<CartItemDto>.toCart(): FashionCart = FashionCart(
    items = mapNotNull(CartItemDto::toDomain),
)

fun CartItemDto.toDomain(): FashionCartItem? {
    val variant = variantId?.takeIf { it.isNotBlank() } ?: return null
    return FashionCartItem(
        variantId = variant,
        storeId = storeId.orEmpty(),
        productName = productName.orEmpty(),
        colorName = colorName?.takeIf(String::isNotBlank),
        size = size?.takeIf(String::isNotBlank),
        unitPriceSum = (unitPrice ?: 0).coerceAtLeast(0),
        // Нулевое или отрицательное количество строки не бывает: сервер
        // удаляет такую строку сам, а показать «0 шт.» значит показать
        // бесплатную покупку.
        quantity = FashionCartRules.normalize(quantity ?: 1),
        serverTotalSum = totalPrice?.takeIf { it >= 0 },
    )
}
