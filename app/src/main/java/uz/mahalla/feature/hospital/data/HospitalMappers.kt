package uz.mahalla.feature.hospital.data

import uz.mahalla.core.format.tiyinToSom
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
    return doctor(doctorId)
}

/**
 * Карточка врача по `id` (issue #181): запрошенный `id` уже известен, поэтому
 * его молчание в ответе — не повод потерять всю карточку, как в списке
 * ([DoctorDto.toDomain]), а повод подставить то, что запрашивали, и разобрать
 * остальные поля как обычно.
 */
internal fun DoctorDto.toDomain(requestedId: String): Doctor =
    doctor(id?.takeIf { it.isNotBlank() } ?: requestedId)

private fun DoctorDto.doctor(doctorId: String): Doctor = Doctor(
    id = doctorId,
    name = name?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
    specialty = specialty?.trim()?.takeIf { it.isNotEmpty() },
    bio = bio?.trim()?.takeIf { it.isNotEmpty() },
    // Отрицательная цена — не скидка, а мусор.
    consultationPriceSum = consultationPrice.tiyinToSom()?.coerceAtLeast(0) ?: 0,
)
