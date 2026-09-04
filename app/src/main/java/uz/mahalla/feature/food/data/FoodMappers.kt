package uz.mahalla.feature.food.data

import uz.mahalla.core.format.parseServerInstant
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
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod

/**
 * Маппинг вертикали «Еда» (эпик 5).
 *
 * Разбор мягкий, как в каталоге: битое поле не роняет меню целиком. Позиции без
 * id или названия выбрасываются — положить в корзину то, что нечем
 * идентифицировать, всё равно нельзя, а пустая строка в списке выглядит багом.
 */

fun List<MenuSectionDto>.toMenu(placeId: String): Menu = Menu(
    placeId = placeId,
    categories = mapNotNull(MenuSectionDto::toDomain),
)

fun MenuSectionDto.toDomain(): MenuCategory? {
    val categoryId = id?.takeIf { it.isNotBlank() } ?: return null
    val items = items.mapNotNull(MenuItemDto::toDomain)
    // Пустая категория — это заголовок без содержимого; в меню он лишний.
    if (items.isEmpty()) return null
    return MenuCategory(id = categoryId, name = name.orEmpty(), items = items)
}

/**
 * Позиция меню. `optionGroups` не заполняется: групп модификаторов в контракте
 * бэкенда нет (см. KDoc `MenuItem`), а собрать их на клиенте значит показать
 * выбор, который в заказ всё равно не уедет.
 */
fun MenuItemDto.toDomain(): MenuItem? {
    val itemId = id?.takeIf { it.isNotBlank() } ?: return null
    val itemName = name?.takeIf { it.isNotBlank() } ?: return null
    return MenuItem(
        id = itemId,
        name = itemName,
        description = description?.takeIf(String::isNotBlank),
        // Отрицательная цена — ошибка сервера, а не подарок.
        priceSum = (price ?: 0).coerceAtLeast(0),
        photoUrl = imageUrl?.takeIf(String::isNotBlank),
        // Молчание сервера — «есть»: убрать позицию из продажи по
        // отсутствующему полю значит закрыть кухню целиком.
        isAvailable = isAvailable ?: available ?: true,
    )
}

/**
 * Заказ из общего `OrderView`.
 *
 * [placeName] сервер в этом ответе не отдаёт вовсе — его подставляет
 * репозиторий из кэша заказов (имя знала корзина, из которой заказ оформили).
 */
fun OrderViewDto.toDomain(placeName: String = ""): Order? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    val itemsSum = itemsAmount ?: 0
    val discount = (discountAmount ?: 0).coerceAtLeast(0)
    return Order(
        id = orderId,
        placeId = placeId.orEmpty(),
        placeName = placeName,
        number = orderNumber?.takeIf(String::isNotBlank),
        status = OrderStatus.fromApi(status),
        method = DeliveryMethod.fromApi(fulfillment),
        payment = PaymentMethod.fromApi(paymentMethod),
        totals = CartTotals(
            subtotalSum = itemsSum.coerceAtLeast(0),
            discountSum = discount,
            deliverySum = (deliveryAmount ?: 0).coerceAtLeast(0),
        ),
        lines = items.mapNotNull(OrderItemViewDto::toDomain),
        createdAt = parseServerInstant(createdAt),
        address = deliveryAddress?.takeIf(String::isNotBlank),
    )
}

/**
 * Строка заказа. Без `itemId` её нельзя ни повторить, ни отличить от соседней —
 * такая строка выбрасывается, как и в каталоге.
 */
fun OrderItemViewDto.toDomain(): CartLine? {
    val id = itemId?.takeIf { it.isNotBlank() } ?: return null
    val count = (quantity ?: 1).coerceAtLeast(1)
    // Сервер отдаёт и цену за единицу, и сумму строки; если единичной нет —
    // считаем её из суммы, иначе строка показалась бы бесплатной.
    val unit = unitPrice ?: totalPrice?.let { it / count } ?: 0
    return CartLine(
        id = CartCalculator.lineId(id, emptySet()),
        itemId = id,
        name = itemName.orEmpty(),
        unitPriceSum = unit.coerceAtLeast(0),
        quantity = count,
    )
}

fun CartLine.toRequest(): OrderItemRequestDto = OrderItemRequestDto(
    itemId = itemId,
    quantity = quantity,
)

fun Order.toEntity(): OrderEntity = OrderEntity(
    id = id,
    placeId = placeId,
    placeName = placeName,
    status = status.apiValue,
    totalSum = totals.totalSum,
    createdAtEpochSeconds = createdAt?.epochSecond ?: 0,
)

/**
 * Черновик корзины в БД. `deliverySum` пишется нулём: стоимость доставки до
 * оформления бэкенд не сообщает, а столбец остался с версии схемы 3 — убирать
 * его отдельной миграцией ради нуля незачем.
 */
fun CartLine.toEntity(placeId: String, placeName: String): CartDraftItemEntity =
    CartDraftItemEntity(
        placeId = placeId,
        lineId = id,
        productId = itemId,
        name = name,
        priceSum = unitPriceSum,
        quantity = quantity,
        placeName = placeName,
        deliverySum = 0,
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
    lines = map(CartDraftItemEntity::toDomain),
)

private const val OPTION_SEPARATOR = ","
