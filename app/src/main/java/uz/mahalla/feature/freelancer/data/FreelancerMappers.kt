package uz.mahalla.feature.freelancer.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerPage

/**
 * Разбор мягкий, как в каталоге (issue #53): мастер **без `id`**
 * отбрасывается — ни открыть его профиль, ни заказать у него нечем (`id` идёт
 * в путь запроса), а в списке он стал бы дубликатом ключа.
 *
 * Всё остальное мастера не прячет: без имени он получит подпись от экрана, без
 * специальности, ставки и рейтинга покажется без них.
 */
internal fun FreelancerDto.toDomain(): Freelancer? {
    val freelancerId = id?.takeIf { it.isNotBlank() } ?: return null
    return Freelancer(
        id = freelancerId,
        name = name?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
        profession = profession?.trim()?.takeIf { it.isNotEmpty() },
        bio = bio?.trim()?.takeIf { it.isNotEmpty() },
        city = city?.trim()?.takeIf { it.isNotEmpty() },
        phone = phone?.trim()?.takeIf { it.isNotEmpty() },
        // Отрицательная ставка — не скидка, а мусор.
        hourlyRateSum = hourlyRate?.coerceAtLeast(0) ?: 0,
        experienceYears = experienceYears?.takeIf { it > 0 },
        // Молчание сервера — «мастер берёт заказы»: спрятать кнопку из-за
        // отсутствующего поля хуже, чем показать её и получить честный отказ.
        isAvailable = isAvailable ?: available ?: true,
        ratingAvg = ratingAvg?.coerceAtLeast(0.0) ?: 0.0,
        ratingCount = ratingCount?.coerceAtLeast(0) ?: 0,
    )
}

/** См. [FreelancerPage.hasMore] — правило подсчёта живёт там. */
internal fun FreelancerPageDto.toDomain(): FreelancerPage = FreelancerPage(
    items = content.mapNotNull(FreelancerDto::toDomain),
    hasMore = hasMore(page = page, totalPages = totalPages, last = last),
)

/**
 * Заказ из списка. Без `id` — отбрасывается: в `LazyColumn` это дубликат
 * ключа, а показать его как отдельную строку всё равно нечем.
 *
 * У только что созданного заказа правило другое ([toCreated]) — там
 * отсутствие `id` не отказ.
 */
internal fun FreelancerOrderDto.toDomain(): FreelancerOrder? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    return order(orderId)
}

/**
 * Только что созданный заказ.
 *
 * Ответ без `id` — **не отказ**: заказ создан, и увидеть его можно в «моих
 * заказах», которые приложение всё равно перечитывает у сервера. Это разница с
 * талоном очереди (issue #96), где ручки чтения нет вовсе и такой ответ
 * приходилось считать негодным; та же логика, что у записи (issue #97).
 */
internal fun FreelancerOrderDto.toCreated(): FreelancerOrder = order(id.orEmpty())

private fun FreelancerOrderDto.order(orderId: String) = FreelancerOrder(
    id = orderId,
    freelancerId = freelancerId?.takeIf { it.isNotBlank() },
    serviceId = serviceId?.takeIf { it.isNotBlank() },
    serviceTitle = serviceTitle?.trim()?.takeIf { it.isNotEmpty() },
    priceSum = priceAmount?.coerceAtLeast(0) ?: 0,
    status = FreelancerOrderStatus.fromApi(status),
    scheduledAt = parseServerInstant(scheduledAt),
    address = address?.trim()?.takeIf { it.isNotEmpty() },
    comment = comment?.trim()?.takeIf { it.isNotEmpty() },
    createdAt = parseServerInstant(createdAt),
)

internal fun FreelancerOrderPageDto.toDomain(): FreelancerOrderPage = FreelancerOrderPage(
    items = content.mapNotNull(FreelancerOrderDto::toDomain),
    hasMore = hasMore(page = page, totalPages = totalPages, last = last),
)

/**
 * Одно правило на обе страницы: `last`, иначе `page`/`totalPages`, иначе
 * останавливаемся. Полное молчание сервера о страницах — повод не показать
 * хвост списка, а не зациклить догрузку одной и той же страницы.
 */
private fun hasMore(page: Int?, totalPages: Int?, last: Boolean?): Boolean = when {
    last != null -> !last
    totalPages != null -> (page ?: 0) + 1 < totalPages
    else -> false
}
