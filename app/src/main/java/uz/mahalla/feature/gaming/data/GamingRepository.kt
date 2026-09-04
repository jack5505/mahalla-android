package uz.mahalla.feature.gaming.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingPage
import uz.mahalla.feature.gaming.domain.GamingBookingValidator
import uz.mahalla.feature.gaming.domain.GamingZone
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Игровые зоны и брони (issue #98).
 *
 * Кэша нет намеренно: доступность зоны и занятость времени меняются в течение
 * дня, и зона, поднятая из Room, предложила бы бронь того, что уже занято (то
 * же правило, что у меню в «Еде», issue #9).
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface GamingRepository {

    /** Зоны заведения. Ручка анонимна — список виден и до входа. */
    suspend fun zones(placeId: String): ApiResult<List<GamingZone>>

    /**
     * Забронировать зону.
     *
     * @param zoneName имя выбранной зоны: в ответе его нет, а подтверждение
     * без имени читается как чужое.
     */
    suspend fun book(
        draft: GamingBookingDraft,
        zoneName: String = "",
    ): ApiResult<GamingBooking>

    /** Свои брони страницами. */
    suspend fun myBookings(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<GamingBookingPage>

    companion object {
        /** Столько же, сколько у «моих заведений»: экран один и тот же по виду. */
        const val PAGE_SIZE = 20

        /** Код отказа, когда черновик не прошёл проверку ещё на клиенте. */
        const val INVALID_DRAFT_CODE = "GAMING_BOOKING_INVALID"
    }
}

@Singleton
class DefaultGamingRepository @Inject constructor(
    private val api: GamingApi,
    private val clock: Clock,
) : GamingRepository {

    override suspend fun zones(placeId: String): ApiResult<List<GamingZone>> =
        apiCall { api.zones(placeId).payload() }
            .map { zones -> zones.mapNotNull { it.toDomain(placeId) } }

    /**
     * Незаполненный черновик в сеть не уходит: 400 от сервера сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения (правило issue #76).
     *
     * Ответ без `id` отказом **не** считается: бронь принята, а идентификатор
     * приложению пока некуда девать — отменять брони у бэкенда нечем
     * (см. [GamingApi]).
     */
    override suspend fun book(
        draft: GamingBookingDraft,
        zoneName: String,
    ): ApiResult<GamingBooking> {
        val startTime = draft.startTime
        if (startTime == null || GamingBookingValidator.validate(draft, clock.instant())
                .isNotEmpty()
        ) {
            return ApiResult.Failure(ApiError.Business(GamingRepository.INVALID_DRAFT_CODE))
        }

        return apiCall {
            api.book(
                CreateGamingBookingRequest(
                    zoneId = draft.zoneId,
                    startTime = gamingRequestTime(startTime),
                    durationHours = draft.durationHours,
                ),
            ).payload()
        }.map { dto ->
            dto.toDomain(zoneName = zoneName, requireId = false)
                // `requireId = false` — единственная причина вернуть `null`
                // отпала, но компилятор об этом не знает: подставляем то, что
                // человек и так выбрал сам.
                ?: GamingBooking(id = "", zoneId = draft.zoneId, zoneName = zoneName)
        }
    }

    override suspend fun myBookings(page: Int, size: Int): ApiResult<GamingBookingPage> =
        apiCall { api.myBookings(page = page.coerceAtLeast(0), size = size).payload() }
            .map(GamingBookingPageDto::toDomain)
}
