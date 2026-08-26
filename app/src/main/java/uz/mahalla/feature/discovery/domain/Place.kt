package uz.mahalla.feature.discovery.domain

import androidx.compose.runtime.Immutable

/** Точка на карте. Отдельный тип, чтобы широту и долготу нельзя было перепутать местами. */
@Immutable
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Место в каталоге (эпик 4). Одна модель на выдачу, карту и карточку: поля,
 * которых нет в кратком ответе, — nullable.
 *
 * `distanceMeters` считает сервер: у него есть координаты пользователя из
 * запроса, и одинаковое расстояние в списке и на карте важнее, чем локальный
 * пересчёт.
 */
@Immutable
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val rating: Double,
    val reviewCount: Int,
    val distanceMeters: Int,
    val isOpenNow: Boolean,
    val address: String? = null,
    val photoUrl: String? = null,
    val point: GeoPoint? = null,
    /** Место из блока «рекомендуем» — признак приходит с сервера. */
    val isRecommended: Boolean = false,
) {
    /** Место без рейтинга (`0`) не должно проходить фильтр «от 4 звёзд». */
    val hasRating: Boolean get() = rating > 0.0 && reviewCount > 0
}
