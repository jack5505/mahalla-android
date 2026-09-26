package uz.mahalla.feature.discovery.data

import uz.mahalla.data.db.entity.PlaceCategoryEntity

/**
 * Категория из ответа сервера → строка кэша (issue #378). Без кода строка
 * бессмысленна — по коду категория и сопоставляется с плиткой, — поэтому
 * такая запись пропускается, а не роняет весь список (разбор мягкий).
 */
fun CategoryDto.toEntity(): PlaceCategoryEntity? {
    val code = code.trim()
    if (code.isEmpty()) return null
    return PlaceCategoryEntity(
        code = code,
        titleUz = titleUz.orEmpty(),
        titleRu = titleRu.orEmpty(),
        sortOrder = sortOrder,
    )
}
