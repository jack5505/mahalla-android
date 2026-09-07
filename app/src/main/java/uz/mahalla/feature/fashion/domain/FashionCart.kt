package uz.mahalla.feature.fashion.domain

/**
 * Строка корзины одежды (`CartItemResponse`).
 *
 * Корзина живёт **на сервере**, а не в Room, как у «Еды»: офлайн-черновика
 * здесь нет вовсе, и единственный ключ строки — [variantId] (им же
 * адресуются `PUT`/`DELETE fashion/cart/{variantId}`). Собственный `id`
 * строки бэкенд тоже отдаёт, но менять по нему ничего нельзя.
 *
 * [totalPriceSum] считает сервер. Своё умножение — только фоллбэк: если
 * сервер сумму строки не назвал, показать ноль значило бы соврать про деньги.
 */
data class FashionCartItem(
    val variantId: String,
    val storeId: String,
    val productName: String,
    val colorName: String? = null,
    val size: String? = null,
    val unitPriceSum: Long = 0,
    val quantity: Int = 1,
    val serverTotalSum: Long? = null,
) {
    val totalSum: Long get() = serverTotalSum ?: (unitPriceSum * quantity)

    /** «Qora · L» — то, чем один вариант отличается от соседнего в списке. */
    val variantLabel: String
        get() = listOfNotNull(
            colorName?.takeIf(String::isNotBlank),
            size?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
}

/**
 * Корзина целиком.
 *
 * Она **общая на все магазины** — `GET fashion/cart` отдаёт один список, и у
 * каждой строки свой `storeId`. А `PlaceOrderRequest` принимает ровно один
 * `placeId`, то есть заказ оформляется по одному магазину за раз. Поэтому
 * корзина показывается разделами: [stores].
 */
data class FashionCart(
    val items: List<FashionCartItem> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Сколько единиц всего — для бейджа на кнопке корзины. */
    val itemCount: Int get() = items.sumOf(FashionCartItem::quantity)

    val totalSum: Long get() = items.sumOf(FashionCartItem::totalSum)

    /**
     * Разделы по магазинам, в порядке появления строк: сортировать по имени
     * магазина нечем — его в ответе корзины нет.
     */
    val stores: List<FashionCartStore>
        get() = items.groupBy(FashionCartItem::storeId)
            .map { (storeId, items) -> FashionCartStore(storeId = storeId, items = items) }

    fun item(variantId: String): FashionCartItem? =
        items.firstOrNull { it.variantId == variantId }

    fun store(storeId: String): FashionCartStore? = stores.firstOrNull { it.storeId == storeId }
}

/** Часть корзины, которая уедет одним заказом. */
data class FashionCartStore(
    val storeId: String,
    val items: List<FashionCartItem> = emptyList(),
) {
    val totalSum: Long get() = items.sumOf(FashionCartItem::totalSum)
    val itemCount: Int get() = items.sumOf(FashionCartItem::quantity)
}

/**
 * Правила количества — те же, что в «Еде»: больше 99 одинаковых вещей это
 * опечатка, а не заказ.
 */
object FashionCartRules {

    const val MAX_QUANTITY = 99

    /**
     * Количество, которое можно отправить серверу. Ноль и меньше — не
     * количество, а удаление строки: у него отдельная ручка (`DELETE`), и
     * подменять её нулём в `PUT` нельзя — что сделает с ним бэкенд, из
     * контракта не следует.
     */
    fun normalize(quantity: Int): Int = quantity.coerceIn(1, MAX_QUANTITY)

    /** Ниже единицы «−» превращается в удаление — так же, как в корзине «Еды». */
    fun isRemoval(quantity: Int): Boolean = quantity < 1
}
