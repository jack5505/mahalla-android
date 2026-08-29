package uz.mahalla.feature.food.data

import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.MenuCategory
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.MenuOption
import uz.mahalla.feature.food.domain.OptionGroup
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.food.domain.PromoCode
import uz.mahalla.feature.food.domain.PromoKind
import java.time.Instant

/**
 * Маппинг вертикали «Еда» (эпик 5).
 *
 * Разбор мягкий, как в каталоге: битое поле не роняет меню целиком. Позиции без
 * id или названия выбрасываются — положить в корзину то, что нечем
 * идентифицировать, всё равно нельзя, а пустая строка в списке выглядит багом.
 */

fun MenuDto.toDomain(placeId: String): Menu = Menu(
    placeId = placeId,
    placeName = placeName,
    categories = categories.mapNotNull(MenuCategoryDto::toDomain),
    deliverySum = deliveryFee.coerceAtLeast(0),
    minOrderSum = minOrder.coerceAtLeast(0),
)

fun MenuCategoryDto.toDomain(): MenuCategory? {
    if (id.isBlank()) return null
    val items = items.mapNotNull(MenuItemDto::toDomain)
    // Пустая категория — это заголовок без содержимого; в меню он лишний.
    if (items.isEmpty()) return null
    return MenuCategory(id = id, name = name, items = items)
}

fun MenuItemDto.toDomain(): MenuItem? {
    if (id.isBlank() || name.isBlank()) return null
    return MenuItem(
        id = id,
        name = name,
        description = description?.takeIf(String::isNotBlank),
        // Отрицательная цена — ошибка сервера, а не подарок.
        priceSum = price.coerceAtLeast(0),
        photoUrl = photoUrl?.takeIf(String::isNotBlank),
        isAvailable = available,
        optionGroups = optionGroups.mapNotNull(OptionGroupDto::toDomain),
    )
}

fun OptionGroupDto.toDomain(): OptionGroup? {
    if (id.isBlank()) return null
    val options = options.mapNotNull(MenuOptionDto::toDomain)
    if (options.isEmpty()) return null
    // maxChoices не меньше minChoices и не меньше единицы: иначе группа
    // получилась бы невыполнимой и кнопка «добавить» никогда не включилась бы.
    val min = minChoices.coerceIn(0, options.size)
    val max = maxChoices.coerceAtLeast(1).coerceAtLeast(min).coerceAtMost(options.size)
    return OptionGroup(id = id, name = name, minChoices = min, maxChoices = max, options = options)
}

fun MenuOptionDto.toDomain(): MenuOption? {
    if (id.isBlank() || name.isBlank()) return null
    return MenuOption(id = id, name = name, priceDeltaSum = priceDelta, isAvailable = available)
}

fun PromoDto.toDomain(): PromoCode = PromoCode(
    code = code,
    // Незнакомый вид — фиксированная сумма: процент от неизвестного правила
    // посчитался бы неверно и разошёлся бы с чеком.
    kind = if (kind.equals("percent", ignoreCase = true)) PromoKind.Percent else PromoKind.Fixed,
    value = value.coerceAtLeast(0),
    minOrderSum = minOrder.coerceAtLeast(0),
    maxDiscountSum = maxDiscount?.takeIf { it > 0 },
)

fun OrderDto.toDomain(): Order = Order(
    id = id,
    placeId = placeId,
    placeName = placeName,
    status = OrderStatus.fromApi(status),
    method = DeliveryMethod.fromApi(method),
    payment = PaymentMethod.fromApi(payment),
    totals = CartTotals(
        subtotalSum = subtotal.coerceAtLeast(0),
        discountSum = discount.coerceAtLeast(0),
        deliverySum = delivery.coerceAtLeast(0),
    ),
    lines = items.map(OrderItemDto::toDomain),
    createdAt = Instant.ofEpochSecond(createdAt),
    address = address?.takeIf(String::isNotBlank),
    comment = comment?.takeIf(String::isNotBlank),
    etaMinutes = etaMinutes?.takeIf { it > 0 },
)

fun OrderItemDto.toDomain(): CartLine {
    val options = optionIds.toSet()
    return CartLine(
        id = CartCalculator.lineId(itemId, options),
        itemId = itemId,
        name = name,
        unitPriceSum = price,
        quantity = quantity.coerceAtLeast(1),
        optionIds = options,
        optionsLabel = optionsLabel,
    )
}

fun CartLine.toDto(): OrderItemDto = OrderItemDto(
    itemId = itemId,
    name = name,
    price = unitPriceSum,
    quantity = quantity,
    optionIds = optionIds.sorted(),
    optionsLabel = optionsLabel,
)

fun Order.toEntity(): OrderEntity = OrderEntity(
    id = id,
    placeId = placeId,
    placeName = placeName,
    status = status.apiValue,
    totalSum = totals.totalSum,
    createdAtEpochSeconds = createdAt.epochSecond,
)

fun CartLine.toEntity(placeId: String, placeName: String, deliverySum: Long): CartDraftItemEntity =
    CartDraftItemEntity(
        placeId = placeId,
        lineId = id,
        productId = itemId,
        name = name,
        priceSum = unitPriceSum,
        quantity = quantity,
        placeName = placeName,
        deliverySum = deliverySum,
        optionIds = optionIds.sorted().joinToString(OPTION_SEPARATOR),
        optionsLabel = optionsLabel,
    )

fun CartDraftItemEntity.toDomain(): CartLine = CartLine(
    id = lineId,
    itemId = productId,
    name = name,
    unitPriceSum = priceSum,
    quantity = quantity,
    optionIds = optionIds.split(OPTION_SEPARATOR).filter(String::isNotBlank).toSet(),
    optionsLabel = optionsLabel,
)

fun List<CartDraftItemEntity>.toCart(placeId: String): Cart = Cart(
    placeId = placeId,
    placeName = firstOrNull()?.placeName.orEmpty(),
    deliverySum = firstOrNull()?.deliverySum ?: 0,
    lines = map(CartDraftItemEntity::toDomain),
)

private const val OPTION_SEPARATOR = ","
