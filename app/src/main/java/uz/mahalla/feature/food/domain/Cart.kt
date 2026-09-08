package uz.mahalla.feature.food.domain

/**
 * Корзина (эпик 5.2). Всегда в рамках одного заведения: смешивать позиции двух
 * кухонь в одном заказе бэкенд не умеет, и предупредить об этом надо на
 * добавлении, а не на checkout'е.
 */
data class Cart(
    val placeId: String,
    val placeName: String,
    val lines: List<CartLine> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Сколько единиц товара всего — для бейджа на кнопке корзины. */
    val itemCount: Int get() = lines.sumOf(CartLine::quantity)

    fun line(lineId: String): CartLine? = lines.firstOrNull { it.id == lineId }
}

/**
 * Строка корзины: позиция меню с конкретным набором модификаторов.
 *
 * Одно и то же блюдо с разными добавками — две строки, поэтому ключ строки
 * ([id]) считается из id позиции и выбранных вариантов
 * ([CartCalculator.lineId]), а не равен id позиции.
 *
 * [unitPriceSum] — цена одной единицы уже с модификаторами: пересчитывать её
 * из меню при каждом показе корзины нельзя, меню могло поменяться, а человек
 * видел другую цену.
 */
data class CartLine(
    val id: String,
    val itemId: String,
    val name: String,
    val unitPriceSum: Long,
    val quantity: Int,
    val optionIds: Set<String> = emptySet(),
    /** Подпись модификаторов для показа: «Katta, Pishloq». */
    val optionsLabel: String = "",
) {
    val totalSum: Long get() = unitPriceSum * quantity
}

/**
 * Итог корзины. Все суммы — целые сумы, отрицательных среди них нет.
 *
 * Скидка и доставка приезжают из ответа о заказе (`OrderView`): до оформления
 * бэкенд не сообщает ни стоимости доставки заведения, ни способа приложить к
 * заказу промокод, поэтому в корзине они нули, а не выдуманные числа.
 */
data class CartTotals(
    val subtotalSum: Long = 0,
    val discountSum: Long = 0,
    val deliverySum: Long = 0,
) {
    val totalSum: Long get() = (subtotalSum - discountSum + deliverySum).coerceAtLeast(0)
    val hasDiscount: Boolean get() = discountSum > 0
}

/**
 * Расчёт корзины — чистые функции. Всё, что связано с деньгами, обязано быть
 * проверяемым без Android: округление и границы количества здесь единственный
 * раз, и ViewModel их не повторяет.
 */
object CartCalculator {

    /** Больше 99 порций одного блюда — это опечатка, а не заказ. */
    const val MAX_QUANTITY = 99

    /**
     * Ключ строки. Варианты сортируются: множество не гарантирует порядок, а
     * ключ обязан совпадать у двух одинаковых добавлений подряд — иначе вместо
     * «+1» появится вторая такая же строка.
     */
    fun lineId(itemId: String, optionIds: Set<String>): String =
        if (optionIds.isEmpty()) itemId else itemId + LINE_ID_SEPARATOR + optionIds.sorted().joinToString(",")

    /**
     * Добавление. Такая же строка (та же позиция, те же модификаторы) не
     * дублируется, а увеличивает количество — с упором в [MAX_QUANTITY].
     */
    fun add(lines: List<CartLine>, line: CartLine): List<CartLine> {
        val added = line.quantity.coerceIn(1, MAX_QUANTITY)
        val existing = lines.firstOrNull { it.id == line.id }
            ?: return lines + line.copy(quantity = added)
        // Количество складывается уже приведённым к [1, MAX]: «добавить»,
        // которое из-за нулевого или отрицательного количества удаляет строку, —
        // ловушка, даже если сейчас так никто не зовёт.
        return setQuantity(lines, line.id, existing.quantity.coerceAtLeast(0) + added)
    }

    /**
     * Новое количество строки. Ноль и меньше — удаление строки: отдельная
     * «пустая» строка в корзине не нужна, а минус на кнопке «−» ведёт именно
     * сюда.
     */
    fun setQuantity(lines: List<CartLine>, lineId: String, quantity: Int): List<CartLine> {
        if (quantity <= 0) return remove(lines, lineId)
        val capped = quantity.coerceAtMost(MAX_QUANTITY)
        return lines.map { if (it.id == lineId) it.copy(quantity = capped) else it }
    }

    fun remove(lines: List<CartLine>, lineId: String): List<CartLine> =
        lines.filterNot { it.id == lineId }

    fun subtotal(lines: List<CartLine>): Long = lines.sumOf(CartLine::totalSum)

    /**
     * Итог. Скидка считается от суммы позиций (доставка не скидывается — это
     * деньги курьера) и никогда не превышает её: скидка «−50 000» на заказ в
     * 30 000 не должна превращаться в долг заведения перед клиентом.
     *
     * И скидка, и доставка приходят параметрами, а не из корзины: считать их
     * на клиенте нечем — их называет сервер в ответе о заказе.
     */
    fun totals(
        lines: List<CartLine>,
        discountSum: Long = 0,
        deliverySum: Long = 0,
    ): CartTotals {
        val subtotal = subtotal(lines)
        return CartTotals(
            subtotalSum = subtotal,
            discountSum = discountSum.coerceIn(0, subtotal),
            deliverySum = deliverySum.coerceAtLeast(0),
        )
    }

    private const val LINE_ID_SEPARATOR = "|"
}
