package uz.mahalla.feature.business.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessMenuItem
import uz.mahalla.feature.business.domain.BusinessMenuSection
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderLine
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.QueueEntry
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

internal fun BusinessOrderDto.toDomain(): BusinessOrder? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    return BusinessOrder(
        id = orderId,
        number = orderNumber?.trim()?.takeIf(String::isNotEmpty),
        status = OrderStatus.fromApi(status),
        method = DeliveryMethod.fromApi(fulfillment),
        payment = PaymentMethod.fromApi(paymentMethod),
        // Отрицательных сумм у заказа не бывает: `-500` в чеке кухня прочитала
        // бы как скидку, а это была бы ошибка сервера, а не скидка.
        itemsSum = itemsAmount.orZero(),
        deliverySum = deliveryAmount.orZero(),
        discountSum = discountAmount.orZero(),
        totalSum = totalAmount.orZero(),
        address = deliveryAddress?.trim()?.takeIf(String::isNotEmpty),
        lines = items.mapNotNull(BusinessOrderItemDto::toDomain),
        createdAt = parseServerInstant(createdAt),
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
        unitPriceSum = unitPrice.orZero(),
        // Сервер, промолчавший об итоге строки, считается умножением: показать
        // «0 so'm» за две порции хуже, чем посчитать самим.
        totalPriceSum = totalPrice?.coerceAtLeast(0) ?: (unitPrice.orZero() * count),
    )
}

/** См. `BusinessOrderPage.hasMore` — правило подсчёта живёт там. */
internal fun BusinessOrderPageDto.toDomain(): BusinessOrderPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return BusinessOrderPage(
        items = content.mapNotNull(BusinessOrderDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
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
        priceSum = price.orZero(),
        prepMinutes = prepMinutes?.takeIf { it > 0 },
        // Молчание сервера — «в продаже»: увести всё меню в стоп-лист из-за
        // пропавшего поля хуже, чем показать лишнюю позицию (issue #9).
        isAvailable = isAvailable ?: true,
        isHalal = isHalal ?: false,
    )
}

private fun Long?.orZero(): Long = this?.coerceAtLeast(0) ?: 0
