package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.promotions.data.PromotionsRepository
import uz.mahalla.feature.promotions.domain.PromoCheckResult
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.promotions.domain.PromotionPage

/**
 * Акции в памяти (issue #104): главная и карточка места проверяются без
 * MockWebServer.
 */
class FakePromotionsRepository : PromotionsRepository {

    var platform: ApiResult<PromotionPage> = ApiResult.Success(PromotionPage())

    var place: ApiResult<List<Promotion>> = ApiResult.Success(emptyList())

    /** Ответ на проверку промокода (issue #180); по умолчанию — отказ. */
    var check: ApiResult<PromoCheckResult> = ApiResult.Success(PromoCheckResult(code = "", valid = false))

    /** С какими размерами страницы просили акции платформы. */
    val requestedSizes = mutableListOf<Int>()

    /** У каких заведений спрашивали акции — в порядке запросов. */
    val requestedPlaces = mutableListOf<String>()

    /** Чем и на какую сумму проверяли промокод — в порядке запросов. */
    val requestedChecks = mutableListOf<Triple<String, String, Long>>()

    override suspend fun platformPromotions(page: Int, size: Int): ApiResult<PromotionPage> {
        requestedSizes += size
        return platform
    }

    override suspend fun placePromotions(placeId: String): ApiResult<List<Promotion>> {
        requestedPlaces += placeId
        return place
    }

    override suspend fun check(
        code: String,
        placeId: String,
        orderAmountSum: Long,
    ): ApiResult<PromoCheckResult> {
        requestedChecks += Triple(code, placeId, orderAmountSum)
        return check
    }
}

/** Акция для тестов: обязательные поля заполнены, остальное задаётся точечно. */
fun promotion(
    id: String,
    title: String = "Aksiya $id",
    placeId: String? = null,
    discountPercent: Int? = null,
    startsAt: java.time.Instant? = null,
    endsAt: java.time.Instant? = null,
) = Promotion(
    id = id,
    title = title,
    placeId = placeId,
    discountPercent = discountPercent,
    startsAt = startsAt,
    endsAt = endsAt,
)
