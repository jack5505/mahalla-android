package uz.mahalla.feature.subscription.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.PlanFeature
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionCharge
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

/**
 * Списание за подписку. Платёж **не про подписку** отбрасывается здесь же: у
 * ручки нет фильтра по назначению, и в истории подписки пополнению кошелька
 * взяться неоткуда. Платёж без `id` отбрасывается по той же причине, что и
 * операция кошелька: в `LazyColumn` он дубликат ключа, а отличить его от
 * соседнего всё равно нечем.
 *
 * **Единица суммы выведена быть не может** — в отличие от кошелька (issue #62)
 * и цен тарифа, у `amount` нет дробного близнеца `amountSom`. Читается как
 * тийины: так названы денежные поля в остальной схеме бэкенда (`mrrTiyin`,
 * `walletRevenueTiyin`), и тот же делитель приложение уже использует, когда
 * отправляет сумму пополнения. Если бэкенд хранит платежи в сумах, суммы в
 * истории окажутся в сто раз меньше — это первое, что надо проверить живым
 * ответом (риск записан в отчёт задачи).
 */
internal fun PaymentTransactionDto.toDomain(): SubscriptionCharge? {
    val chargeId = id?.takeIf { it.isNotBlank() } ?: return null
    if (!SubscriptionCharge.isSubscriptionPurpose(purpose)) return null
    return SubscriptionCharge(
        id = chargeId,
        // Отрицательное списание — ошибка сервера: «−49 000» в истории платежей
        // не значит ничего.
        amountSum = WalletAmounts.toSom(amount, WalletAmounts.TIYIN_IN_SOM).coerceAtLeast(0),
        status = ChargeStatus.fromServer(status),
        provider = ChargeProvider.fromServer(provider),
        purpose = purpose?.takeIf { it.isNotBlank() },
        errorMessage = errorMessage?.takeIf { it.isNotBlank() },
        createdAt = parseServerInstant(createdAt),
    )
}

/**
 * Есть ли у сервера ещё страницы платежей. Считается как у кошелька: по
 * `last`, а без него — по `page`/`totalPages`. Полного молчания о страницах
 * достаточно, чтобы остановиться: лучше не показать хвост истории, чем
 * зациклить догрузку одной и той же страницы.
 *
 * @param requestedPage номер запрошенной страницы: сервер, не вернувший
 * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая
 * (issue #53).
 */
internal fun PaymentTransactionPageDto.hasMore(requestedPage: Int): Boolean = when {
    last != null -> !last
    totalPages != null -> requestedPage + 1 < totalPages
    else -> false
}

private const val MAX_PERCENT = 100
