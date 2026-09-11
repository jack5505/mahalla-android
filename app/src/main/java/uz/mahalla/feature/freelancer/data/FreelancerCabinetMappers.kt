package uz.mahalla.feature.freelancer.data

import uz.mahalla.core.format.Money
import uz.mahalla.core.format.tiyinToSom
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft

/**
 * Разбор мягкий, как у остальных сущностей вертикали (issue #107): услуга без
 * `id` отбрасывается — ни поправить, ни удалить её нечем (`id` в пути
 * запроса), а в списке она стала бы дубликатом ключа.
 *
 * В отличие от каталога ([uz.mahalla.feature.freelancer.data.toDomain] для
 * клиента), неактивная услуга здесь **не** фильтруется: мастер должен видеть
 * и включить обратно то, что сам выключил.
 */
internal fun FreelancerServiceDto.toDomain(): FreelancerCabinetService? {
    val serviceId = id?.takeIf { it.isNotBlank() } ?: return null
    return FreelancerCabinetService(
        id = serviceId,
        title = title?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        priceSum = priceAmount.tiyinToSom()?.coerceAtLeast(0) ?: 0,
        durationMinutes = durationMinutes?.coerceAtLeast(0) ?: 0,
        isActive = isActive ?: true,
    )
}

/**
 * Тело запроса из черновика. Вызывается только после проверки
 * [uz.mahalla.feature.freelancer.domain.FreelancerServiceFormValidator] —
 * `!!` здесь безопасен ровно потому, что репозиторий не даёт до него дойти
 * с неполным черновиком (см. [DefaultFreelancerCabinetRepository]).
 */
internal fun FreelancerServiceDraft.toRequest(): FreelancerServiceRequest = FreelancerServiceRequest(
    title = trimmedTitle,
    description = trimmedDescription.takeIf(String::isNotEmpty),
    priceAmount = Money.somToTiyin(priceSum!!),
    durationMinutes = durationMinutes!!,
    isActive = isActive,
)
