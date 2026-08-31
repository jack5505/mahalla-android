package uz.mahalla.feature.food.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.MenuCategory
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.food.domain.PromoCode
import java.time.Instant

/**
 * Маппинг вертикали «Еда» под контракт бэкенда (issue #63).
 *
 * Разбор мягкий, как в каталоге: битое поле не роняет меню целиком. Позиции без
 * id или названия выбрасываются — положить в корзину то, что нечем
 * идентифицировать, всё равно нельзя, а пустая строка в списке выглядит багом.
 */

/**
 * Меню — это список «меню» бэкенда, каждое со своими позициями; для приложения
 * они и есть категории.
 *
 * [placeName] и стоимость доставки в контракт меню не входят: имя подставляет
 * репозиторий из кэша мест, доставку считает сервер при оформлении.
 */
fun List<MenuSectionDto>.toMenu(placeId: String, placeName: String = ""): Menu = Menu(
    placeId = placeId,
    placeName = placeName,
    categories = mapNotNull(MenuSectionDto::toDomain),
)

fun MenuSectionDto.toDomain(): MenuCategory? {
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
        isAvailable = isAvailable,
    )
}

/**
 * Ответ проверки промокода. `valid: false` сюда не доходит — репозиторий
 * превращает его в отказ: скидка 0 с «код применён» объяснению не поддаётся.
 */
fun PromoCheckDto.toDomain(code: String, subtotalSum: Long): PromoCode = PromoCode(
    code = promoCode?.takeIf(String::isNotBlank) ?: code,
    discountSum = discountAmount.coerceIn(0, subtotalSum),
    checkedSubtotalSum = subtotalSum,
)

/**
 * Заказ. [placeName] бэкенд не отдаёт — его подставляет репозиторий из кэша
 * мест; пустое имя лучше выдуманного.
 */
fun OrderDto.toDomain(placeName: String = ""): Order = Order(
    id = id,
    placeId = placeId,
    placeName = placeName,
    orderNumber = orderNumber?.trim().orEmpty(),
    status = OrderStatus.fromApi(status),
    method = DeliveryMethod.fromApi(fulfillment),
    payment = PaymentMethod.fromApi(paymentMethod),
    totals = CartTotals(
        subtotalSum = itemsAmount.coerceAtLeast(0),
        discountSum = discountAmount.coerceAtLeast(0),
        deliverySum = deliveryAmount.coerceAtLeast(0),
    ),
    totalSum = totalAmount.coerceAtLeast(0),
    lines = items.map(OrderItemDto::toDomain),
    // Дата не разобралась — берём эпоху, а не «сейчас»: заказ прошлой недели,
    // помеченный сегодняшним числом, врёт убедительнее пустого места.
    createdAt = parseServerInstant(createdAt) ?: Instant.EPOCH,
    address = deliveryAddress?.takeIf(String::isNotBlank),
)

/**
 * Позиция заказа. Модификаторов в контракте нет, поэтому ключ строки равен id
 * позиции — ровно то, что вернёт `CartCalculator.lineId` с пустым набором.
 */
fun OrderItemDto.toDomain(): CartLine = CartLine(
    id = itemId,
    itemId = itemId,
    name = itemName,
    unitPriceSum = unitPrice,
    quantity = quantity.coerceAtLeast(1),
)

/** Строка корзины в тело заказа: сервер принимает только позицию и количество. */
fun CartLine.toRequestDto(): OrderItemRequestDto = OrderItemRequestDto(
    itemId = itemId,
    quantity = quantity,
)

fun Order.toEntity(): OrderEntity = OrderEntity(
    id = id,
    placeId = placeId,
    placeName = placeName,
    status = status.apiValue,
    totalSum = totalSum,
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
