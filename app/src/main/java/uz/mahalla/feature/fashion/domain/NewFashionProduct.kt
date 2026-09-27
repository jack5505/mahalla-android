package uz.mahalla.feature.fashion.domain

import androidx.compose.runtime.Immutable

/**
 * Черновик нового товара витрины (issue #280, продолжение #252,
 * `POST fashion/stores/{storeId}/products`).
 *
 * Обязательны только [name] и цена — остальное та же схема `ProductDetail`
 * не ограничивает нигде, кроме их присутствия как полей. `priceText`
 * разбирается тем же приёмом, что и у аптеки
 * ([uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft]): цифры
 * выбираются из строки, а не парсятся как есть.
 */
@Immutable
data class NewFashionProductDraft(
    val name: String = "",
    val description: String = "",
    val brand: String = "",
    val material: String = "",
    val careInstructions: String = "",
    val sizeGuide: String = "",
    val gender: ProductGender? = null,
    val categoryId: String? = null,
    val priceText: String = "",
) {
    /** `null`, если в поле нет ни одной цифры. */
    val priceSum: Long? get() = priceText.filter(Char::isDigit).takeIf(String::isNotEmpty)
        ?.toLongOrNull()

    val isNameValid: Boolean get() = name.isNotBlank()

    val isPriceValid: Boolean get() = priceSum != null

    val canSubmit: Boolean get() = isNameValid && isPriceValid

    fun withName(value: String): NewFashionProductDraft = copy(name = value)
    fun withDescription(value: String): NewFashionProductDraft = copy(description = value)
    fun withBrand(value: String): NewFashionProductDraft = copy(brand = value)
    fun withMaterial(value: String): NewFashionProductDraft = copy(material = value)
    fun withCareInstructions(value: String): NewFashionProductDraft =
        copy(careInstructions = value)
    fun withSizeGuide(value: String): NewFashionProductDraft = copy(sizeGuide = value)

    /** Повторный тап по уже выбранному значению снимает выбор — то же правило, что у категории. */
    fun withGender(value: ProductGender): NewFashionProductDraft =
        copy(gender = value.takeIf { it != gender })
    fun withCategory(value: String?): NewFashionProductDraft =
        copy(categoryId = value.takeIf { it != categoryId })
    fun withPrice(value: String): NewFashionProductDraft = copy(priceText = value)

    companion object {
        /** Тот же приём, что у [uz.mahalla.feature.role.domain.ProviderForm]. */
        const val INVALID_CODE = "FASHION_PRODUCT_FORM_INVALID"
    }
}

/**
 * Черновик нового варианта товара — размер/цвет (issue #280,
 * `POST fashion/products/{id}/variants`).
 *
 * Обязательны [colorName], [size] и цена — ровно те поля `VariantResponse`,
 * без которых строка каталога ничего не значит (вариант без цвета или
 * размера нечем отличить от соседнего, а без цены нечем продать).
 * [colorHex] и [sku] в схеме есть, но ничем, кроме присутствия поля, не
 * ограничены.
 */
@Immutable
data class NewFashionVariantDraft(
    val colorName: String = "",
    val colorHex: String = "",
    val size: String = "",
    val sku: String = "",
    val priceText: String = "",
    val stockText: String = "",
) {
    val priceSum: Long? get() = priceText.filter(Char::isDigit).takeIf(String::isNotEmpty)
        ?.toLongOrNull()

    /** Остаток необязателен: пустое поле — «не считали на складе», а не ноль. */
    val stockQuantity: Int? get() = stockText.filter(Char::isDigit).takeIf(String::isNotEmpty)
        ?.toIntOrNull()

    val isColorValid: Boolean get() = colorName.isNotBlank()
    val isSizeValid: Boolean get() = size.isNotBlank()
    val isPriceValid: Boolean get() = priceSum != null
    val isStockValid: Boolean get() = stockText.isBlank() || stockQuantity != null

    val canSubmit: Boolean
        get() = isColorValid && isSizeValid && isPriceValid && isStockValid

    fun withColorName(value: String): NewFashionVariantDraft = copy(colorName = value)
    fun withColorHex(value: String): NewFashionVariantDraft = copy(colorHex = value)
    fun withSize(value: String): NewFashionVariantDraft = copy(size = value)
    fun withSku(value: String): NewFashionVariantDraft = copy(sku = value)
    fun withPrice(value: String): NewFashionVariantDraft = copy(priceText = value)
    fun withStock(value: String): NewFashionVariantDraft = copy(stockText = value)

    companion object {
        const val INVALID_CODE = "FASHION_VARIANT_FORM_INVALID"
    }
}
