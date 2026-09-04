package uz.mahalla.feature.hospital.data

import uz.mahalla.feature.hospital.domain.Doctor

/**
 * Разбор мягкий, как в каталоге (issue #53): врач **без `id`** отбрасывается —
 * записаться к нему всё равно нечем (`doctorId` обязателен в теле записи), а в
 * списке он стал бы дубликатом ключа.
 *
 * Всё остальное врача не прячет: без имени он получит подпись от экрана, без
 * специальности и цены покажется без них.
 */
internal fun DoctorDto.toDomain(): Doctor? {
    val doctorId = id?.takeIf { it.isNotBlank() } ?: return null
    return Doctor(
        id = doctorId,
        name = name?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
        specialty = specialty?.trim()?.takeIf { it.isNotEmpty() },
        bio = bio?.trim()?.takeIf { it.isNotEmpty() },
        // Отрицательная цена — не скидка, а мусор.
        consultationPriceSum = consultationPrice?.coerceAtLeast(0) ?: 0,
    )
}
