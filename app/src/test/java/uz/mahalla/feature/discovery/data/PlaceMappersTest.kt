package uz.mahalla.feature.discovery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.PlaceCategory
import java.time.Instant

/**
 * Разбор ответа каталога (эпик 4, контракт бэкенда — issue #53).
 *
 * Главное правило проверок: одно битое поле не должно уносить весь список —
 * каталог это витрина, и место без координат лучше показать без метки, чем
 * потерять экран.
 */
class PlaceMappersTest {

    @Test
    fun `summary maps to the domain model`() {
        val dto = PlaceSummaryDto(
            id = "p-1",
            name = "Osh markazi",
            category = "FOOD",
            address = "Amir Temur 1",
            latitude = 41.31,
            longitude = 69.28,
            isAvailable = true,
            ratingAvg = 4.6,
            ratingCount = 42,
            distanceMeters = 320.7,
            logoUrl = "logo.jpg",
        )

        val place = dto.toDomain()

        assertEquals("p-1", place.id)
        assertEquals(PlaceCategory.Food, place.category)
        assertEquals(GeoPoint(41.31, 69.28), place.point)
        assertEquals(4.6, place.rating, 0.0)
        assertEquals(42, place.reviewCount)
        assertEquals("расстояние сервера округляется вниз", 320, place.distanceMeters)
        assertTrue(place.isOpenNow)
        assertEquals("logo.jpg", place.photoUrl)
    }

    @Test
    fun `a search hit gets its distance measured locally`() {
        // В ответе поиска расстояния нет вовсе — без пересчёта у всей выдачи
        // стояло бы «0 м».
        val dto = PlaceDocumentDto(id = "p", name = "P", latitude = 41.3157, longitude = 69.2797)

        val place = dto.toDomain(DeviceLocation(latitude = 41.3111, longitude = 69.2797))

        assertTrue("${place.distanceMeters} м", place.distanceMeters in 400..600)
    }

    @Test
    fun `without coordinates the distance stays zero instead of a made up number`() {
        val dto = PlaceDocumentDto(id = "p", name = "P")

        assertEquals(0, dto.toDomain(DeviceLocation(41.3111, 69.2797)).distanceMeters)
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        val dto = PlaceSummaryDto(id = "p", name = "P", latitude = 41.31, longitude = null)

        assertNull(dto.toDomain().point)
    }

    @Test
    fun `coordinates out of range are dropped`() {
        val dto = PlaceSummaryDto(id = "p", name = "P", latitude = 500.0, longitude = 69.28)

        assertNull(dto.toDomain().point)
    }

    @Test
    fun `blank strings become nulls`() {
        val dto = PlaceSummaryDto(id = "p", name = "P", address = "  ", logoUrl = "")

        val place = dto.toDomain()

        assertNull(place.address)
        assertNull(place.photoUrl)
    }

    @Test
    fun `the cover comes before the logo and duplicates are dropped`() {
        // Логотип это иконка, а не фотография заведения: в галерее он не может
        // стоять первым, а вторым экземпляром — тем более.
        val dto = PlaceDetailDto(
            id = "p",
            name = "P",
            coverUrl = "cover.jpg",
            logoUrl = "cover.jpg",
        )

        assertEquals(listOf("cover.jpg"), dto.toDetails().photos)
    }

    @Test
    fun `the card falls back to the city when there is no address`() {
        val dto = PlaceDetailDto(id = "p", name = "P", address = "  ", city = "Toshkent")

        assertEquals("Toshkent", dto.toDetails().contacts.address)
    }

    @Test
    fun `review timestamp is parsed in both formats and a broken one is dropped`() {
        val instant = ReviewDto(id = "r", createdAt = "2026-08-25T10:15:30Z").toDomain()
        // Jackson сериализует LocalDateTime без зоны — такой отзыв тоже обязан
        // получить дату, иначе она пуста у всех.
        val local = ReviewDto(id = "r", createdAt = "2026-08-25T10:15:30.123").toDomain()
        val broken = ReviewDto(id = "r", createdAt = "вчера").toDomain()

        assertEquals(Instant.parse("2026-08-25T10:15:30Z"), instant.createdAt)
        assertEquals(Instant.parse("2026-08-25T10:15:30.123Z"), local.createdAt)
        assertNull(broken.createdAt)
    }

    @Test
    fun `a place survives a round trip through the cache`() {
        val place = PlaceSummaryDto(
            id = "p-1",
            name = "Osh markazi",
            category = "FOOD",
            address = "Amir Temur 1",
            latitude = 41.31,
            longitude = 69.28,
            isAvailable = true,
            ratingAvg = 4.6,
            ratingCount = 42,
            distanceMeters = 320.0,
            logoUrl = "logo.jpg",
        ).toDomain()

        val restored = place.toEntity(updatedAtEpochSeconds = 1_774_000_000L).toDomain()

        assertEquals(place, restored)
    }

    @Test
    fun `unknown category is normalized on the way into the cache`() {
        // В базе хранится apiValue; неизвестное значение превращается в Other,
        // и после чтения обратно оно не «оживает» как настоящая категория.
        val entity = PlaceSummaryDto(id = "p", name = "P", category = "MOSQUE").toDomain().toEntity(0)

        assertEquals("", entity.category)
        assertEquals(PlaceCategory.Other, entity.toDomain().category)
    }

    @Test
    fun `cached details are marked as cached and carry no schedule`() {
        val details = PlaceSummaryDto(id = "p", name = "P")
            .toDomain()
            .toEntity(0, description = "Text", phone = "+998901234567")
            .toCachedDetails()

        assertTrue(details.fromCache)
        assertTrue(details.hours.isEmpty())
        assertTrue(details.reviews.isEmpty())
        assertEquals("+998901234567", details.contacts.phone)
        assertEquals("Text", details.description)
    }
}
