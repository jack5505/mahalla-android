package uz.mahalla.feature.promotions.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.promotions.domain.PromoType
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.promotions.domain.PromotionFeed
import uz.mahalla.feature.promotions.domain.PromotionPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Акции (issue #104).
 *
 * Кэша нет намеренно: акция заканчивается по расписанию заведения, и
 * показанная из Room скидка, которой уже нет, — прямое враньё про деньги.
 *
 * Интерфейс — ради тестов ViewModel: главная и карточка места проверяются без
 * MockWebServer.
 */
interface PromotionsRepository {

    /** Акции платформы: страницами, для блока на главной. */
    suspend fun platformPromotions(
        page: Int = 0,
        size: Int = PromotionFeed.HOME_PAGE_SIZE,
    ): ApiResult<PromotionPage>

    /** Акции одного заведения: пагинации у этой ручки нет, приходит список. */
    suspend fun placePromotions(placeId: String): ApiResult<List<Promotion>>
}

@Singleton
class DefaultPromotionsRepository @Inject constructor(
    private val api: PromotionsApi,
) : PromotionsRepository {

    override suspend fun platformPromotions(page: Int, size: Int): ApiResult<PromotionPage> =
        apiCall { api.platform(page = page.coerceAtLeast(0), size = size).payload() }
            .map(PromotionPageDto::toDomain)

    override suspend fun placePromotions(placeId: String): ApiResult<List<Promotion>> =
        apiCall { api.placePromotions(placeId).payload() }
            .map { promotions -> promotions.mapNotNull(PromotionDto::toDomain) }
}

/**
 * `hasMore` считается по `last`, а при его отсутствии — по `page`/`totalPages`.
 * Полного молчания сервера о страницах достаточно, чтобы остановиться: лучше
 * не показать хвост, чем зациклить догрузку одной и той же страницы (то же
 * правило, что у уведомлений, issue #81).
 */
internal fun PromotionPageDto.toDomain(): PromotionPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return PromotionPage(
        items = content.mapNotNull(PromotionDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
}

/**
 * Разбор мягкий, как в каталоге (issue #53).
 *
 * Отбрасываются две записи. Без `id` — потому что в `LazyColumn` это дубликат
 * ключа, а отличить её от соседней всё равно нечем. Без заголовка **и** без
 * описания — потому что показывать в карточке нечего: пустая плашка «акция»
 * читается как поломка экрана. Если заголовка нет, но описание есть, оно и
 * становится заголовком: текст у акции всё-таки есть.
 *
 * Всё остальное акцию не прячет: незнакомый вид, мусор вместо процента и
 * битые даты — не повод скрыть от человека скидку, которую завело заведение.
 */
internal fun PromotionDto.toDomain(): Promotion? {
    val promotionId = id?.takeIf(String::isNotBlank) ?: return null
    val text = title?.takeIf(String::isNotBlank)
        ?: description?.takeIf(String::isNotBlank)
        ?: return null
    return Promotion(
        id = promotionId,
        title = text,
        description = description?.takeIf(String::isNotBlank)?.takeIf { it != text },
        type = PromoType.fromServer(promoType),
        placeId = placeId?.takeIf(String::isNotBlank),
        // Процент вне 1..100 — ошибка сервера: «скидка 0 %» и «скидка 1000 %»
        // одинаково нечего показывать.
        discountPercent = discountPercent?.takeIf { it in PERCENT_RANGE },
        discountAmount = discountAmount?.takeIf { it > 0 },
        minOrderAmount = minOrderAmount?.takeIf { it > 0 },
        promoCode = promoCode?.takeIf(String::isNotBlank),
        startsAt = parseServerInstant(startedAt),
        endsAt = parseServerInstant(endedAt),
        isPlatformWide = isPlatformWide ?: platformWide ?: false,
        // Молчание сервера — «работает»: спрятать акцию заведения из-за
        // отсутствующего поля хуже, чем показать выключенную.
        isActive = isActive ?: active ?: true,
        isValid = valid,
    )
}

private val PERCENT_RANGE = 1..100
