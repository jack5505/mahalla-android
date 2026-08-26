package uz.mahalla.feature.discovery.data

import uz.mahalla.data.db.entity.PlaceEntity
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceCapabilities
import uz.mahalla.feature.place.domain.PlaceContacts
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * DTO ↔ домен ↔ Room (эпик 4).
 *
 * Разбор «мягкий»: битое поле в ответе не роняет весь список. Каталог —
 * витрина, и одно место без координат лучше показать без метки на карте, чем
 * потерять экран целиком.
 */

fun PlaceDto.toDomain(): Place = Place(
    id = id,
    name = name,
    category = PlaceCategory.fromApi(category),
    rating = rating,
    reviewCount = reviewCount,
    distanceMeters = distanceMeters,
    isOpenNow = isOpenNow,
    address = address?.takeIf(String::isNotBlank),
    photoUrl = photoUrl?.takeIf(String::isNotBlank),
    point = geoPoint(latitude, longitude),
    isRecommended = isRecommended,
)

fun PlaceDto.toDetails(reviews: List<Review> = emptyList()): PlaceDetails = PlaceDetails(
    place = toDomain(),
    description = description?.takeIf(String::isNotBlank),
    // Главное фото первым и без дублей: сервер иногда присылает его и в
    // photoUrl, и в photos.
    photos = (listOfNotNull(photoUrl?.takeIf(String::isNotBlank)) + photos)
        .filter(String::isNotBlank)
        .distinct(),
    hours = openingHours.mapNotNull(OpeningHoursDto::toDomainOrNull),
    contacts = PlaceContacts(
        phone = phone?.takeIf(String::isNotBlank),
        website = website?.takeIf(String::isNotBlank),
        address = address?.takeIf(String::isNotBlank),
    ),
    capabilities = PlaceCapabilities(queue = hasQueue, booking = hasBooking, ordering = hasOrdering),
    reviews = reviews,
)

fun OpeningHoursDto.toDomainOrNull(): OpeningHours? {
    val day = try {
        DayOfWeek.of(dayOfWeek)
    } catch (invalidDay: DateTimeException) {
        return null
    }
    val opens = parseTime(opensAt)
    val closes = parseTime(closesAt)
    // Половина интервала бессмысленна: «открыто с 9:00 и никогда не закрыто»
    // показать нечем, поэтому такой день считаем выходным.
    return if (opens == null || closes == null) {
        OpeningHours(day, opensAt = null, closesAt = null)
    } else {
        OpeningHours(day, opens, closes)
    }
}

fun ReviewDto.toDomain(): Review = Review(
    id = id,
    author = author,
    rating = rating,
    text = text,
    createdAt = parseInstant(createdAt),
)

fun PlaceDto.toEntity(updatedAtEpochSeconds: Long): PlaceEntity = PlaceEntity(
    id = id,
    name = name,
    category = PlaceCategory.fromApi(category).apiValue,
    rating = rating,
    distanceMeters = distanceMeters,
    isOpenNow = isOpenNow,
    updatedAtEpochSeconds = updatedAtEpochSeconds,
    reviewCount = reviewCount,
    address = address,
    photoUrl = photoUrl,
    latitude = latitude,
    longitude = longitude,
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

private fun geoPoint(latitude: Double?, longitude: Double?): GeoPoint? =
    if (latitude != null && longitude != null &&
        latitude in LATITUDE_RANGE && longitude in LONGITUDE_RANGE
    ) {
        GeoPoint(latitude, longitude)
    } else {
        null
    }

private fun parseTime(value: String?): LocalTime? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        LocalTime.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}

private fun parseInstant(value: String?): Instant? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        Instant.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}

private val LATITUDE_RANGE = -90.0..90.0
private val LONGITUDE_RANGE = -180.0..180.0
