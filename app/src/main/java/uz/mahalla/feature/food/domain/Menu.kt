package uz.mahalla.feature.food.domain

/**
 * Меню заведения (эпик 5.1).
 *
 * Стоп-лист — не отдельный список, а флаг [MenuItem.isAvailable] у позиции:
 * блюдо, которого сегодня нет, из меню не исчезает (иначе человек будет искать
 * его глазами и решит, что ошибся заведением), а показывается неактивным.
 */
data class Menu(
    val placeId: String,
    /** Название заведения: корзина показывает его до всякой сети. */
    val placeName: String = "",
    val categories: List<MenuCategory>,
    /** Стоимость доставки заведения; 0 — бесплатно или самовывоз. */
    val deliverySum: Long = 0,
    /** Минимальная сумма заказа; 0 — ограничения нет. */
    val minOrderSum: Long = 0,
) {
    val isEmpty: Boolean get() = categories.all { it.items.isEmpty() }

    /** Все позиции одним списком — для поиска по id из корзины. */
    fun item(itemId: String): MenuItem? =
        categories.firstNotNullOfOrNull { category ->
            category.items.firstOrNull { it.id == itemId }
        }
}

data class MenuCategory(
    val id: String,
    val name: String,
    val items: List<MenuItem>,
)

/**
 * Позиция меню. Цена — целые сумы (см. `MoneyFormatter`).
 *
 * [optionGroups] пуст у простых позиций: шторка модификаторов тогда не нужна и
 * позиция кладётся в корзину одним нажатием.
 */
data class MenuItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val priceSum: Long,
    val photoUrl: String? = null,
    /** `false` — стоп-лист: позиция видна, но в корзину не кладётся. */
    val isAvailable: Boolean = true,
    val optionGroups: List<OptionGroup> = emptyList(),
) {
    val hasOptions: Boolean get() = optionGroups.isNotEmpty()
}

/**
 * Группа модификаторов: «размер» (ровно один), «добавки» (сколько угодно).
 *
 * Границы задаёт сервер парой [minChoices]/[maxChoices] — так описываются все
 * встречающиеся случаи, и клиенту не нужен отдельный флаг «обязательная».
 */
data class OptionGroup(
    val id: String,
    val name: String,
    val minChoices: Int = 0,
    val maxChoices: Int = 1,
    val options: List<MenuOption> = emptyList(),
) {
    val isRequired: Boolean get() = minChoices > 0

    /**
     * Одиночный выбор — это радио-группа: нажатие на другой вариант заменяет
     * прежний, а не добавляет второй.
     */
    val isSingleChoice: Boolean get() = maxChoices <= 1

    fun option(optionId: String): MenuOption? = options.firstOrNull { it.id == optionId }

    /** Варианты из стоп-листа выбрать нельзя — они рисуются неактивными. */
    val availableOptions: List<MenuOption> get() = options.filter { it.isAvailable }
}

/** Модификатор. [priceDeltaSum] может быть и отрицательной («без мяса — дешевле»). */
data class MenuOption(
    val id: String,
    val name: String,
    val priceDeltaSum: Long = 0,
    val isAvailable: Boolean = true,
)
