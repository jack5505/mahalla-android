package uz.mahalla.feature.discovery.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.data.db.entity.PlaceEntity
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.feature.discovery.domain.GeoDistance
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.place.domain.PlaceContacts
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review

/**
 * DTO ↔ домен ↔ Room (эпик 4, контракт бэкенда — issue #53).
 *
 * Разбор «мягкий»: битое поле в ответе не роняет весь список. Каталог —
 * витрина, и одно место без координат лучше показать без метки на карте, чем
 * потерять экран целиком.
 */

/**
 * @param from координаты пользователя. Нужны только как запасной способ
 * посчитать расстояние: сервер присылает его сам, но в ответе поиска этого
 * поля нет.
 */
fun PlaceSummaryDto.toDomain(from: DeviceLocation? = null): Place {
    val point = geoPoint(latitude, longitude)
    return Place(
        id = id,
        name = name,
        category = PlaceCategory.fromApi(category),
        rating = ratingAvg,
        reviewCount = ratingCount,
        distanceMeters = distanceMeters?.toInt() ?: distanceTo(from, point),
        // «Работает сейчас» у бэкенда одно поле на всё: расписания в контракте
        // нет, есть переключатель «принимаем заказы».
        isOpenNow = isAvailable,
        address = address?.takeIf(String::isNotBlank),
        photoUrl = logoUrl?.takeIf(String::isNotBlank),
        point = point,
    )
}

/**
 * Место из поискового индекса. Ни адреса, ни числа отзывов там нет, поэтому
 * карточка в выдаче показывает меньше — но показывает.
 */
fun PlaceDocumentDto.toDomain(from: DeviceLocation? = null): Place {
    val point = geoPoint(latitude, longitude)
    return Place(
        id = id,
        name = name,
        category = PlaceCategory.fromApi(category),
        rating = ratingAvg,
        reviewCount = 0,
        distanceMeters = distanceTo(from, point),
        isOpenNow = isActive,
        address = city?.takeIf(String::isNotBlank),
        point = point,
    )
}

fun PlaceDetailDto.toDomain(from: DeviceLocation? = null): Place {
    val point = geoPoint(latitude, longitude)
    return Place(
        id = id,
        name = name,
        category = PlaceCategory.fromApi(category),
        rating = ratingAvg,
        reviewCount = ratingCount,
        distanceMeters = distanceTo(from, point),
        isOpenNow = isAvailable,
        address = address?.takeIf(String::isNotBlank),
        photoUrl = logoUrl?.takeIf(String::isNotBlank),
        point = point,
    )
}

/**
 * Карточка. Расписания бэкенд не отдаёт (`hours` пуст), а вертикали —
 * очередь, бронь, заказ — определяются категорией: у каждой из них свой
 * контроллер (`food/…/menu`, `barber-services/…`, `gaming/…/zones`).
 */
fun PlaceDetailDto.toDetails(
    reviews: List<Review> = emptyList(),
    from: DeviceLocation? = null,
): PlaceDetails = PlaceDetails(
    place = toDomain(from),
    description = description?.takeIf(String::isNotBlank),
    // Обложка первой: логотип это иконка, а не фотография заведения.
    photos = listOfNotNull(coverUrl, logoUrl).filter(String::isNotBlank).distinct(),
    contacts = PlaceContacts(
        phone = phone?.takeIf(String::isNotBlank),
        website = website?.takeIf(String::isNotBlank),
        address = address?.takeIf(String::isNotBlank) ?: city?.takeIf(String::isNotBlank),
    ),
    reviews = reviews,
)

fun ReviewDto.toDomain(): Review = Review(
    id = id,
    author = author,
    rating = rating,
    text = text,
    createdAt = parseServerInstant(createdAt),
    authorId = userId?.takeIf(String::isNotBlank),
    avatarUrl = avatarUrl?.takeIf(String::isNotBlank),
)

fun Place.toEntity(
    updatedAtEpochSeconds: Long,
    description: String? = null,
    phone: String? = null,
    website: String? = null,
): PlaceEntity = PlaceEntity(
    id = id,
    name = name,
    category = category.apiValue,
    rating = rating,
    distanceMeters = distanceMeters,
    isOpenNow = isOpenNow,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
    reviewCount = reviewCount,
    address = address,
    photoUrl = photoUrl,
    latitude = point?.latitude,
    longitude = point?.longitude,
    isRecommended = isRecommended,
    description = description,
    phone = phone,
    website = website,
)

fun PlaceEntity.toDomain(): Place = Place(
    id = id,
    name = name,
    category = PlaceCategory.fromApi(category),
    rating = rating,
    reviewCount = reviewCount,
    distanceMeters = distanceMeters,
    isOpenNow = isOpenNow,
    address = address,
    photoUrl = photoUrl,
    point = geoPoint(latitude, longitude),
    isRecommended = isRecommended,
)

/**
 * Карточка из кэша: только то, что действительно лежит в базе. Расписания и
 * отзывов здесь нет — [PlaceDetails.fromCache] говорит экрану, что блоки
 * пусты не потому, что их нет у места.
 */
fun PlaceEntity.toCachedDetails(): PlaceDetails = PlaceDetails(
    place = toDomain(),
    description = description,
    photos = listOfNotNull(photoUrl),
    contacts = PlaceContacts(phone = phone, website = website, address = address),
    fromCache = true,
)

/** Ноль вместо честного расстояния — ложь; без координат остаётся только он. */
private fun distanceTo(from: DeviceLocation?, point: GeoPoint?): Int =
    if (from != null && point != null) {
        GeoDistance.meters(GeoPoint(from.latitude, from.longitude), point)
    } else {
        0
    }

private fun geoPoint(latitude: Double?, longitude: Double?): GeoPoint? =
    if (latitude != null && longitude != null &&
        latitude in LATITUDE_RANGE && longitude in LONGITUDE_RANGE
    ) {
        GeoPoint(latitude, longitude)
    } else {
        null
    }

private val LATITUDE_RANGE = -90.0..90.0
private val LONGITUDE_RANGE = -180.0..180.0
