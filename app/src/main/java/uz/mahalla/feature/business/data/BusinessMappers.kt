package uz.mahalla.feature.business.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.tiyinToSom
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessMenuItem
import uz.mahalla.feature.business.domain.BusinessMenuSection
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderLine
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.fashion.data.FashionStoreOrderDto
import uz.mahalla.feature.fashion.data.FashionStoreOrderItemDto
import uz.mahalla.feature.fashion.data.OrderPageDto
import uz.mahalla.feature.food.data.OrderItemViewDto
import uz.mahalla.feature.food.data.OrderViewDto
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.queue.domain.WalkInStatus

/**
 * Разбор ответов бизнес-панели (эпик #16).
 *
 * Правило общее для всего проекта: **мягко**. Запись без `id` отбрасывается —
 * действовать над ней всё равно нечем, а в `LazyColumn` она стала бы
 * дубликатом ключа. Всё остальное показывается как есть: незнакомый статус
 * доезжает `Unknown`, пропавшее имя заменяет экран.
 */

internal fun QueueEntryDto.toDomain(): QueueEntry? {
    val ticketId = id?.takeIf { it.isNotBlank() } ?: return null
    return QueueEntry(
        id = ticketId,
        userName = userName?.trim().orEmpty(),
        serviceName = serviceName?.trim()?.takeIf(String::isNotEmpty),
        status = WalkInStatus.fromApi(status),
        // Отрицательная позиция — заведомо мусор: в очереди нет места «минус
        // первый», а показанное «-1» читалось бы как «его уже вызвали».
        queuePosition = queuePosition?.takeIf { it > 0 },
        estimatedWaitMinutes = estimatedWaitMinutes?.takeIf { it >= 0 },
        note = barberNote?.trim()?.takeIf(String::isNotEmpty),
        createdAt = parseServerInstant(createdAt),
    )
}

/**
 * @param vertical чей это заказ — ответ статус-ручки своей вертикали не
 * называет (`FoodOrderResponse` её не знает вовсе), поэтому её передаёт
 * вызывающий: `DefaultBusinessRepository` уже знает, в какую ветку он попал.
 */
internal fun BusinessOrderDto.toDomain(vertical: PlaceCategory = PlaceCategory.Food): BusinessOrder? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    return BusinessOrder(
        id = orderId,
        number = orderNumber?.trim()?.takeIf(String::isNotEmpty),
        status = OrderStatus.fromApi(status),
        method = DeliveryMethod.fromApi(fulfillment),
        payment = PaymentMethod.fromApi(paymentMethod),
        // Отрицательных сумм у заказа не бывает: `-500` в чеке кухня прочитала
        // бы как скидку, а это была бы ошибка сервера, а не скидка.
        itemsSum = itemsAmount.toSomOrZero(),
        deliverySum = deliveryAmount.toSomOrZero(),
        discountSum = discountAmount.toSomOrZero(),
        totalSum = totalAmount.toSomOrZero(),
        address = deliveryAddress?.trim()?.takeIf(String::isNotEmpty),
        lines = items.mapNotNull(BusinessOrderItemDto::toDomain),
        createdAt = parseServerInstant(createdAt),
        vertical = vertical,
    )
}

/**
 * Строка заказа без `itemId` **не отбрасывается**: кухне важно, что заказали,
 * а не идентификатор позиции — та могла быть удалена из меню после заказа.
 * Ключом строки в списке служит её номер, а не id.
 */
private fun BusinessOrderItemDto.toDomain(): BusinessOrderLine? {
    val title = itemName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val count = quantity?.coerceAtLeast(0) ?: 0
    return BusinessOrderLine(
        itemId = itemId.orEmpty(),
        name = title,
        quantity = count,
        unitPriceSum = unitPrice.toSomOrZero(),
        // Сервер, промолчавший об итоге строки, считается умножением в
        // тийинах и переводится в сумы один раз, как и весь остальной проект
        // (issue #149): показать «0 so'm» за две порции хуже, чем посчитать
        // самим.
        totalPriceSum = totalPrice?.tiyinToSom()?.coerceAtLeast(0)
            ?: (unitPrice.orZero() * count).tiyinToSom().coerceAtLeast(0),
    )
}

/**
 * Заказы «Одежды» (issue #187): статус смены `fashion/stores/{id}/orders/
 * {orderId}/status`, схема `FashionOrderResponse`. Статус, способ получения и
 * оплаты — те же перечисления, что и у «Еды» (сверено живым `/v3/api-docs`
 * 2026-09-19), второй набор под вертикаль заводить не пришлось.
 *
 * @param vertical см. [BusinessOrderDto.toDomain] — тот же приём.
 */
internal fun FashionStoreOrderDto.toDomain(
    vertical: PlaceCategory = PlaceCategory.Fashion,
): BusinessOrder? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    return BusinessOrder(
        id = orderId,
        number = orderNumber?.trim()?.takeIf(String::isNotEmpty),
        status = OrderStatus.fromApi(status),
        method = DeliveryMethod.fromApi(fulfillment),
        payment = PaymentMethod.fromApi(paymentMethod),
        itemsSum = itemsAmount.toSomOrZero(),
        deliverySum = deliveryAmount.toSomOrZero(),
        discountSum = discountAmount.toSomOrZero(),
        totalSum = totalAmount.toSomOrZero(),
        address = deliveryAddress?.trim()?.takeIf(String::isNotEmpty),
        lines = items.mapNotNull(FashionStoreOrderItemDto::toDomain),
        createdAt = parseServerInstant(createdAt),
        vertical = vertical,
    )
}

/**
 * Строка по варианту, а не по позиции меню: у `FashionOrderItemResponse` нет
 * `itemId`/`itemName` «Еды», только `variantId` и разложенные цвет/размер.
 * Имя строки собирается из товара, цвета и размера — заводить под них
 * отдельные поля в [BusinessOrderLine] значило бы разойтись с «Едой» ради
 * данных, нужных только на одном экране.
 */
private fun FashionStoreOrderItemDto.toDomain(): BusinessOrderLine? {
    val title = productName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val count = quantity?.coerceAtLeast(0) ?: 0
    return BusinessOrderLine(
        itemId = variantId.orEmpty(),
        name = listOfNotNull(
            title,
            colorName?.trim()?.takeIf(String::isNotEmpty),
            size?.trim()?.takeIf(String::isNotEmpty),
        ).joinToString(", "),
        quantity = count,
        unitPriceSum = unitPrice.toSomOrZero(),
        totalPriceSum = totalPrice?.tiyinToSom()?.coerceAtLeast(0)
            ?: (unitPrice.orZero() * count).tiyinToSom().coerceAtLeast(0),
    )
}

/**
 * Единая лента (issue #289): `GET places/{placeId}/orders`, схема
 * `OrderView` — общая для всех вертикалей, `vertical` называет саму
 * вертикаль, и её не нужно передавать снаружи, в отличие от
 * [BusinessOrderDto.toDomain]/[FashionStoreOrderDto.toDomain], где ответ
 * статус-ручки о своей вертикали молчит.
 */
internal fun OrderViewDto.toDomain(): BusinessOrder? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    return BusinessOrder(
        id = orderId,
        number = orderNumber?.trim()?.takeIf(String::isNotEmpty),
        status = OrderStatus.fromApi(status),
        method = DeliveryMethod.fromApi(fulfillment),
        payment = PaymentMethod.fromApi(paymentMethod),
        itemsSum = itemsAmount.toSomOrZero(),
        deliverySum = deliveryAmount.toSomOrZero(),
        discountSum = discountAmount.toSomOrZero(),
        totalSum = totalAmount.toSomOrZero(),
        address = deliveryAddress?.trim()?.takeIf(String::isNotEmpty),
        lines = items.mapNotNull(OrderItemViewDto::toDomain),
        createdAt = parseServerInstant(createdAt),
        // Незнакомая или пропавшая вертикаль — `Other`: доменное правило
        // [uz.mahalla.feature.business.domain.BusinessOrderStatusFlow.canChangeStatus]
        // уже прячет кнопки смены статуса у всего, что не «Еда»/«Одежда», и
        // `Other` в их число не входит по построению.
        vertical = PlaceCategory.fromApi(vertical),
    )
}

/** Строка единой ленты — то же правило мягкого разбора, что у [BusinessOrderItemDto.toDomain]. */
private fun OrderItemViewDto.toDomain(): BusinessOrderLine? {
    val title = itemName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val count = quantity?.coerceAtLeast(0) ?: 0
    return BusinessOrderLine(
        itemId = itemId.orEmpty(),
        name = title,
        quantity = count,
        unitPriceSum = unitPrice.toSomOrZero(),
        totalPriceSum = totalPrice?.tiyinToSom()?.coerceAtLeast(0)
            ?: (unitPrice.orZero() * count).tiyinToSom().coerceAtLeast(0),
    )
}

/** См. `BusinessOrderPage.hasMore` — правило подсчёта живёт там. */
internal fun OrderPageDto.toDomain(): BusinessOrderPage = BusinessOrderPage(
    items = content.mapNotNull(OrderViewDto::toDomain),
    hasMore = hasMorePages(last = last, page = page, totalPages = totalPages),
)

private fun hasMorePages(last: Boolean?, page: Int?, totalPages: Int?): Boolean = when {
    last != null -> !last
    totalPages != null -> (page ?: 0) + 1 < totalPages
    else -> false
}

/**
 * Меню целиком.
 *
 * Раздел без `id` отбрасывается вместе с позициями: `menuId` обязателен для
 * `CreateItemRequest`, то есть в такой раздел всё равно нечего добавить, а
 * показать его значило бы предложить кнопку, которая не работает.
 */
internal fun List<MenuSectionDto>.toDomain(): BusinessMenu = BusinessMenu(
    sections = mapNotNull { section ->
        val sectionId = section.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BusinessMenuSection(
            id = sectionId,
            name = section.name?.trim().orEmpty(),
            items = section.items.mapNotNull(MenuItemDto::toDomain),
        )
    },
)

internal fun MenuItemDto.toDomain(): BusinessMenuItem? {
    val itemId = id?.takeIf { it.isNotBlank() } ?: return null
    return BusinessMenuItem(
        id = itemId,
        name = name?.trim().orEmpty(),
        description = description?.trim()?.takeIf(String::isNotEmpty),
        priceSum = price.toSomOrZero(),
        prepMinutes = prepMinutes?.takeIf { it > 0 },
        // Молчание сервера — «в продаже»: увести всё меню в стоп-лист из-за
        // пропавшего поля хуже, чем показать лишнюю позицию (issue #9).
        isAvailable = isAvailable ?: true,
        isHalal = isHalal ?: false,
    )
}

private fun Long?.orZero(): Long = this?.coerceAtLeast(0) ?: 0

/** Тийины бэкенда → целые сумы, как и везде в проекте (issue #149). */
private fun Long?.toSomOrZero(): Long = (this ?: 0).tiyinToSom().coerceAtLeast(0)
