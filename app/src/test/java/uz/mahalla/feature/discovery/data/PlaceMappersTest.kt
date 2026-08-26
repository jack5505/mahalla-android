package uz.mahalla.feature.discovery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.PlaceCategory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Разбор ответа каталога (эпик 4).
 *
 * Главное правило проверок: одно битое поле не должно уносить весь список —
 * каталог это витрина, и место без координат лучше показать без метки, чем
 * потерять экран.
 */
class PlaceMappersTest {

    @Test
    fun `place dto maps to the domain model`() {
        val dto = PlaceDto(
            id = "p-1",
            name = "Osh markazi",
            category = "food",
            rating = 4.6,
            distanceMeters = 320,
            isOpenNow = true,
            reviewCount = 42,
            address = "Amir Temur 1",
            latitude = 41.31,
            longitude = 69.28,
            isRecommended = true,
        )

        val place = dto.toDomain()

        assertEquals("p-1", place.id)
        assertEquals(PlaceCategory.Food, place.category)
        assertEquals(GeoPoint(41.31, 69.28), place.point)
        assertEquals(42, place.reviewCount)
        assertTrue(place.isRecommended)
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        val dto = PlaceDto(id = "p", name = "P", latitude = 41.31, longitude = null)

        assertNull(dto.toDomain().point)
    }

    @Test
    fun `coordinates out of range are dropped`() {
        val dto = PlaceDto(id = "p", name = "P", latitude = 500.0, longitude = 69.28)

        assertNull(dto.toDomain().point)
    }

    @Test
    fun `blank strings become nulls`() {
        val dto = PlaceDto(id = "p", name = "P", address = "  ", photoUrl = "")

        val place = dto.toDomain()

        assertNull(place.address)
        assertNull(place.photoUrl)
    }

    @Test
    fun `main photo comes first and is not duplicated`() {
        val dto = PlaceDto(
            id = "p",
            name = "P",
            photoUrl = "main.jpg",
            photos = listOf("main.jpg", "second.jpg", ""),
        )

        assertEquals(listOf("main.jpg", "second.jpg"), dto.toDetails().photos)
    }

    @Test
    fun `opening hours parse into the domain schedule`() {
        val dto = OpeningHoursDto(dayOfWeek = 1, opensAt = "09:00", closesAt = "18:00")

        val hours = dto.toDomainOrNull()!!

        assertEquals(DayOfWeek.MONDAY, hours.dayOfWeek)
        assertEquals(LocalTime.of(9, 0), hours.opensAt)
        assertEquals(LocalTime.of(18, 0), hours.closesAt)
    }

    @Test
    fun `an impossible day of week is skipped instead of crashing`() {
        assertNull(OpeningHoursDto(dayOfWeek = 9, opensAt = "09:00", closesAt = "18:00").toDomainOrNull())
    }

    @Test
    fun `half an interval is treated as a day off`() {
        // «Открыто с 9:00 и никогда не закрыто» показать нечем.
        val hours = OpeningHoursDto(dayOfWeek = 3, opensAt = "09:00", closesAt = null).toDomainOrNull()!!

        assertTrue(hours.isDayOff)
    }

    @Test
    fun `broken time is treated as a day off`() {
        val hours = OpeningHoursDto(dayOfWeek = 3, opensAt = "утром", closesAt = "18:00").toDomainOrNull()!!

        assertTrue(hours.isDayOff)
    }

    @Test
    fun `review timestamp is parsed and a broken one is dropped`() {
        val parsed = ReviewDto(id = "r", createdAt = "2026-08-25T10:15:30Z").toDomain()
        val broken = ReviewDto(id = "r", createdAt = "вчера").toDomain()

        assertEquals(Instant.parse("2026-08-25T10:15:30Z"), parsed.createdAt)
        assertNull(broken.createdAt)
    }

    @Test
    fun `dto survives a round trip through the cache`() {
        val dto = PlaceDto(
            id = "p-1",
            name = "Osh markazi",
            category = "food",
            rating = 4.6,
            distanceMeters = 320,
            isOpenNow = true,
            reviewCount = 42,
            address = "Amir Temur 1",
            photoUrl = "main.jpg",
            latitude = 41.31,
            longitude = 69.28,
            isRecommended = true,
            description = "Osh va somsa",
            phone = "+998901234567",
        )

        val restored = dto.toEntity(updatedAtEpochSeconds = 1_774_000_000L).toDomain()

        assertEquals(dto.toDomain(), restored)
    }

    @Test
    fun `unknown category is normalized on the way into the cache`() {
        // В базе хранится apiValue; неизвестное значение превращается в Other,
        // и после чтения обратно оно не «оживает» как настоящая категория.
        val entity = PlaceDto(id = "p", name = "P", category = "barbershop").toEntity(0)

        assertEquals("", entity.category)
        assertEquals(PlaceCategory.Other, entity.toDomain().category)
    }

    @Test
    fun `cached details are marked as cached and carry no schedule`() {
        val details = PlaceDto(id = "p", name = "P", phone = "+998901234567", description = "Text")
            .toEntity(0)
            .toCachedDetails()

        assertTrue(details.fromCache)
        assertTrue(details.hours.isEmpty())
        assertTrue(details.reviews.isEmpty())
        assertEquals("+998901234567", details.contacts.phone)
    }
}
