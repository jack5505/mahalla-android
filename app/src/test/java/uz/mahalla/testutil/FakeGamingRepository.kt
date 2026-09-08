package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.gaming.data.GamingRepository
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingPage
import uz.mahalla.feature.gaming.domain.GamingBookingStatus
import uz.mahalla.feature.gaming.domain.GamingZone

/** Игровые зоны в памяти (issue #98): экраны проверяются без MockWebServer. */
class FakeGamingRepository : GamingRepository {

    /** Что вернёт `zones`. */
    var zonesResult: ApiResult<List<GamingZone>> = ApiResult.Success(listOf(gamingZone()))

    /** Что вернёт `book`; `null` — подтверждённая бронь из черновика. */
    var bookResult: ApiResult<GamingBooking>? = null

    /** Страницы «моих броней» по номеру; всё, чего нет, — пустая страница. */
    val pages = mutableMapOf<Int, ApiResult<GamingBookingPage>>()

    val booked = mutableListOf<Pair<GamingBookingDraft, String>>()

    val requestedPages = mutableListOf<Int>()

    override suspend fun zones(placeId: String): ApiResult<List<GamingZone>> = zonesResult

    override suspend fun book(
        draft: GamingBookingDraft,
        zoneName: String,
    ): ApiResult<GamingBooking> {
        booked += draft to zoneName
        return bookResult ?: ApiResult.Success(
            GamingBooking(
                id = "b-1",
                zoneId = draft.zoneId,
                zoneName = zoneName,
                startTime = draft.startTime,
                durationHours = draft.durationHours,
                status = GamingBookingStatus.Confirmed,
            ),
        )
    }

    override suspend fun myBookings(page: Int, size: Int): ApiResult<GamingBookingPage> {
        requestedPages += page
        return pages[page] ?: ApiResult.Success(GamingBookingPage())
    }
}

/** Зона для тестов: значения по умолчанию — открытая и с ценой. */
fun gamingZone(
    id: String = "z-1",
    placeId: String = "p-1",
    name: String = "PlayStation 5",
    pricePerHour: Long = 30_000,
    isAvailable: Boolean = true,
): GamingZone = GamingZone(
    id = id,
    placeId = placeId,
    name = name,
    pricePerHour = pricePerHour,
    isAvailable = isAvailable,
)

/** Бронь для тестов. */
fun gamingBooking(
    id: String = "b-1",
    zoneName: String = "PlayStation 5",
    status: GamingBookingStatus = GamingBookingStatus.Confirmed,
): GamingBooking = GamingBooking(id = id, zoneName = zoneName, status = status)
