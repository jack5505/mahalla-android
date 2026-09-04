package uz.mahalla.feature.subscription.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.PlanFeature
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionAmounts
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import uz.mahalla.feature.wallet.domain.WalletAmounts

/**
 * Разбор ответов подписки (issue #103). Мягкий, как в каталоге (issue #53):
 * незнакомое значение перечисления или отсутствующее поле тариф не прячут.
 *
 * Единственное исключение — **тариф без `code`**: оформить его нечем
 * (`planCode` обязателен в `SubscribeRequest`), а в `LazyColumn` он ещё и
 * дубликат ключа. Такой тариф отбрасывается.
 */
internal fun PlanDto.toDomain(): SubscriptionPlan? {
    val planCode = code?.takeIf { it.isNotBlank() } ?: return null
    val scale = SubscriptionAmounts.scaleOf(
        monthly = monthlyPrice,
        monthlySom = monthlyPriceSom,
        yearly = yearlyPrice,
        yearlySom = yearlyPriceSom,
    )
    return SubscriptionPlan(
        code = planCode,
        name = name?.takeIf { it.isNotBlank() },
        nameUz = nameUz?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        audience = PlanAudience.fromServer(audience),
        tier = tier?.takeIf { it.isNotBlank() },
        // Отрицательная цена — ошибка сервера: «−10 000 в месяц» на карточке
        // тарифа не значит ничего, а на решение влияет как ноль.
        monthlySum = WalletAmounts.toSom(monthlyPrice, scale).coerceAtLeast(0),
        yearlySum = WalletAmounts.toSom(yearlyPrice, scale).coerceAtLeast(0),
        amountScale = scale,
        yearlyDiscountPercent = yearlyDiscountPercent?.coerceIn(0, MAX_PERCENT) ?: 0,
        trialDays = trialDays?.coerceAtLeast(0) ?: 0,
        isFree = isFree ?: free ?: false,
        isPopular = isPopular ?: popular ?: false,
        features = features(),
        // Ноль и отрицательное значение лимитом не считаются: «заведений: 0» в
        // списке возможностей — это не возможность. Что бэкенд обозначает
        // «безлимитом», из контракта не следует (см. риски issue #103).
        maxPlaces = maxPlaces?.takeIf { it > 0 },
        maxListings = maxListings?.takeIf { it > 0 },
        maxPhotosPerListing = maxPhotosPerListing?.takeIf { it > 0 },
        freePromotionsMonthly = freePromotionsMonthly?.takeIf { it > 0 },
        analyticsLevel = analyticsLevel?.takeIf { it.isNotBlank() && !it.equals("NONE", true) },
    )
}

/** Порядок — из [PlanFeature]: он одинаков у всех тарифов, иначе их не сравнить. */
private fun PlanDto.features(): Set<PlanFeature> = buildSet {
    if (noAds == true) add(PlanFeature.NoAds)
    if (hasVerifiedBadge == true) add(PlanFeature.VerifiedBadge)
    if (hasFeaturedListing == true) add(PlanFeature.FeaturedListing)
    if (hasPrioritySupport == true) add(PlanFeature.PrioritySupport)
    if (hasMultiStaff == true) add(PlanFeature.MultiStaff)
    if (hasCustomBranding == true) add(PlanFeature.CustomBranding)
    if (hasApiAccess == true) add(PlanFeature.ApiAccess)
}

/**
 * Подписка. Обязательных полей нет: отменять и переключать автопродление
 * бэкенд разрешает без единого идентификатора (у обеих ручек нет ни пути с
 * `id`, ни поля в теле), то есть ответ без `id` вполне рабочий.
 *
 * Даты разбираются общим [parseServerInstant]: Jackson отдаёт `LocalDateTime`
 * без зоны, и иначе срок подписки был бы пуст у всех.
 */
internal fun SubscriptionDto.toDomain(): Subscription {
    val scale = WalletAmounts.scaleOf(pricePaid, pricePaidSom)
    return Subscription(
        id = id?.takeIf { it.isNotBlank() },
        planCode = planCode?.takeIf { it.isNotBlank() },
        planName = planName?.takeIf { it.isNotBlank() },
        status = SubscriptionStatus.fromServer(status),
        billingPeriod = BillingPeriod.fromServer(billingPeriod),
        pricePaidSum = WalletAmounts.toSom(pricePaid, scale).coerceAtLeast(0),
        startedAt = parseServerInstant(startedAt),
        expiresAt = parseServerInstant(expiresAt),
        autoRenew = autoRenew ?: false,
        isTrial = isTrial ?: trial ?: false,
        // Отрицательный остаток — «уже кончилось»: показывать «−3 дня» незачем.
        daysRemaining = daysRemaining?.coerceAtLeast(0),
        isActive = isActive ?: active ?: (SubscriptionStatus.fromServer(status) == SubscriptionStatus.Active),
        inGracePeriod = inGracePeriod ?: false,
    )
}

private const val MAX_PERCENT = 100
