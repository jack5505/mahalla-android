package uz.mahalla.feature.fashion.domain

/**
 * Вариант товара (`VariantResponse`): конкретный размер конкретного цвета.
 *
 * **В корзину кладётся вариант, а не товар** — это главное отличие вертикали
 * «Одежда» от «Еды»: ключ строки корзины задаёт сервер, и им же является
 * [id].
 *
 * [stockQuantity] необязателен: `null` — «сервер не считает остатки», а не
 * «ноль». Разница существенная — по нулю кнопка выключается.
 */
data class ProductVariant(
    val id: String,
    val colorName: String,
    val size: String,
    val colorHex: String? = null,
    val sku: String? = null,
    val priceSum: Long = 0,
    val stockQuantity: Int? = null,
    val isAvailable: Boolean = true,
) {
    /**
     * Можно ли купить. Молчание сервера о наличии — «есть»: спрятать товар из
     * продажи по отсутствующему полю значит закрыть магазин целиком (то же
     * правило, что у стоп-листа в «Еде»).
     */
    val isOrderable: Boolean get() = isAvailable && (stockQuantity == null || stockQuantity > 0)
}

/**
 * Карточка товара (`ProductDetail`).
 *
 * Варианты бэкенд отдаёт картой «цвет → варианты» (`variantsByColor`), а
 * здесь они уже разложены плоским списком: порядок цветов и размеров внутри
 * цвета сохраняется таким, каким его прислал сервер — это порядок, в котором
 * их завёл магазин, и переупорядочивать его на клиенте незачем.
 */
data class FashionProductDetail(
    val id: String,
    val storeId: String,
    val name: String,
    val description: String? = null,
    val brand: String? = null,
    val material: String? = null,
    val careInstructions: String? = null,
    val sizeGuide: String? = null,
    val gender: ProductGender = ProductGender.Unknown,
    val basePriceSum: Long = 0,
    val salePriceSum: Long? = null,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val isNew: Boolean = false,
    val isBestseller: Boolean = false,
    val variants: List<ProductVariant> = emptyList(),
) {
    /** Цвета в порядке магазина, без повторов. */
    val colors: List<String> get() = variants.map(ProductVariant::colorName).distinct()

    fun variantsOf(color: String): List<ProductVariant> =
        variants.filter { it.colorName == color }

    fun variant(variantId: String?): ProductVariant? =
        variantId?.let { id -> variants.firstOrNull { it.id == id } }

    /**
     * Цена варианта, если он выбран, иначе цена товара. У варианта своя цена
     * (`VariantResponse.price`): XXL может стоить дороже S, и показывать общую
     * цену там, где платят другую, нельзя.
     */
    fun priceOf(variant: ProductVariant?): Long =
        variant?.priceSum?.takeIf { it > 0 }
            ?: salePriceSum?.takeIf { it > 0 && it < basePriceSum }
            ?: basePriceSum

    /** Хоть один вариант в продаже: иначе на карточке нечего нажимать. */
    val hasOrderableVariant: Boolean get() = variants.any(ProductVariant::isOrderable)
}

/**
 * Выбор варианта — чистые функции (issue #108).
 *
 * Правила выбора цвета и размера обязаны быть проверяемыми без Android: их
 * ошибка стоит человеку не того размера в посылке, а скриншотом такое не
 * ловится.
 */
object VariantSelection {

    /**
     * Что выбрано при открытии карточки: первый доступный вариант, а если
     * доступных нет — первый вообще (тогда карточка показывает товар, но
     * кнопка выключена; пустая карточка объясняла бы меньше).
     */
    fun initial(detail: FashionProductDetail): ProductVariant? =
        detail.variants.firstOrNull(ProductVariant::isOrderable)
            ?: detail.variants.firstOrNull()

    /**
     * Смена цвета. Размер сохраняется, если он есть в новом цвете: человек
     * выбирает размер один раз и дальше листает цвета, а сброс на первый
     * размер каждый раз — способ уехать не в своём размере.
     *
     * Нет такого размера — берётся первый доступный вариант этого цвета.
     */
    fun selectColor(
        detail: FashionProductDetail,
        color: String,
        current: ProductVariant?,
    ): ProductVariant? {
        val ofColor = detail.variantsOf(color)
        if (ofColor.isEmpty()) return current
        val sameSize = current?.size?.let { size -> ofColor.firstOrNull { it.size == size } }
        return sameSize
            ?: ofColor.firstOrNull(ProductVariant::isOrderable)
            ?: ofColor.first()
    }

    /**
     * Смена размера внутри выбранного цвета. Незнакомый id ничего не меняет:
     * это вариант из прошлой карточки, доехавший на уже сменившийся товар.
     */
    fun selectVariant(
        detail: FashionProductDetail,
        variantId: String,
        current: ProductVariant?,
    ): ProductVariant? = detail.variant(variantId) ?: current
}
