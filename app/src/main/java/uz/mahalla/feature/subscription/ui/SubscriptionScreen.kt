package uz.mahalla.feature.subscription.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanFeature
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Подписка (issue #103, эпик #13): тарифы, оформление, пробный период, отмена
 * и автопродление.
 *
 * Названия тарифов приходят с бэкенда двумя полями (`name` и `nameUz`) и
 * выбираются по языку приложения: своих строк под них в приложении нет
 * намеренно — новый тариф сервера иначе приехал бы безымянным.
 */
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Срок идёт и без участия приложения, а отменить подписку могли и в другом
    // месте: показанное час назад «активна» ничего не стоит.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(SubscriptionEvent.ScreenResumed)
    }

    SubscriptionContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun SubscriptionContentScreen(
    state: SubscriptionState,
    onEvent: (SubscriptionEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Язык приложения (эпик 1.5) — из ресурсов, а не из системной локали: язык
    // выбирает пользователь внутри приложения, и `values/` (uz) против
    // `values-ru/` отвечают на это точнее, чем `Locale`.
    val uzbek = stringResource(R.string.plan_name_language) == UZBEK_TAG

    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.subscription_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(SubscriptionEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                item(key = "current") {
                    CurrentBlock(state = state, uzbek = uzbek, onEvent = onEvent)
                }

                // Экран после действия не уходит (уводить некуда), поэтому об
                // успехе он говорит словами: молчание читается как «ничего не
                // произошло» (issue #49).
                state.notice?.let { notice ->
                    item(key = "notice") {
                        NoticeCard(
                            notice = notice,
                            onDismiss = { onEvent(SubscriptionEvent.NoticeDismissed) },
                        )
                    }
                }

                // Отказ действия — над списком, а не вместо него: тарифы уже
                // на экране, и прятать их незачем.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { InlineFailure(failure = failure) }
                }

                item(key = "plans-header") {
                    SectionHeader(title = stringResource(R.string.subscription_plans_title))
                }
                item(key = "period") {
                    PeriodSelector(
                        period = state.period,
                        enabled = !state.isBusy,
                        onSelect = { onEvent(SubscriptionEvent.PeriodSelected(it)) },
                    )
                }

                planItems(state = state, uzbek = uzbek, onEvent = onEvent)
            }
        }
    }

    if (state.confirmCancel) {
        MahallaDialog(
            title = stringResource(R.string.subscription_cancel_title),
            text = stringResource(R.string.subscription_cancel_message),
            confirmLabel = stringResource(R.string.subscription_cancel),
            onConfirm = { onEvent(SubscriptionEvent.CancelConfirmed) },
            onDismiss = { onEvent(SubscriptionEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.subscription_cancel_keep),
            destructive = true,
        )
    }
}

/**
 * Текущая подписка. Состояния разложены руками, а не через `ScreenStateHost`:
 * тот рисует `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn`
 * вложенная прокрутка меряется бесконечной высотой (issue #62).
 */
@Composable
private fun CurrentBlock(
    state: SubscriptionState,
    uzbek: Boolean,
    onEvent: (SubscriptionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val current = state.current) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)

        // Подписки нет — это не ошибка, а самое частое состояние: ради него
        // экран и открывают.
        is ScreenState.Empty -> MahallaCard(modifier = modifier) {
            Text(
                text = stringResource(R.string.subscription_none_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.subscription_none_description),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        is ScreenState.Error -> MahallaCard(modifier = modifier) {
            InlineFailure(
                failure = current.failure,
                onRetry = { onEvent(SubscriptionEvent.CurrentRetry) },
            )
        }

        is ScreenState.Content -> CurrentCard(
            subscription = current.data,
            planName = state.planName(current.data, uzbek),
            state = state,
            onEvent = onEvent,
            modifier = modifier,
        )
    }
}

/**
 * Имя тарифа берётся из списка тарифов: там есть узбекское название, а в
 * ответе про подписку — только одно поле `planName`. Тарифа в списке нет
 * (пропал, ещё не приехал) — показывается то, что назвал сервер, и лишь потом
 * код.
 */
private fun SubscriptionState.planName(subscription: Subscription, uzbek: Boolean): String {
    val fromPlans = (plans as? ScreenState.Content)?.data
        ?.firstOrNull { it.isSameCode(subscription.planCode) }
        ?.displayName(uzbek)
    return fromPlans
        ?: subscription.planName
        ?: subscription.planCode
        ?: ""
}

@Composable
private fun CurrentCard(
    subscription: Subscription,
    planName: String,
    state: SubscriptionState,
    onEvent: (SubscriptionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subscription_current_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgMuted,
                )
                Text(
                    text = planName.ifBlank { stringResource(R.string.subscription_plan_unnamed) },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MahallaBadge(
                text = stringResource(subscription.status.labelRes()),
                tone = subscription.status.tone(),
            )
        }

        // Пробный период — главное, что человек должен знать про свою
        // подписку: с него начнут списывать деньги.
        if (subscription.isTrial) {
            Row(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = stringResource(R.string.subscription_trial_badge),
                    tone = MahallaTone.Info,
                )
            }
        }

        subscription.billingPeriod?.let { period ->
            InfoRow(
                label = stringResource(R.string.subscription_period_label),
                value = stringResource(period.labelRes()),
            )
        }

        if (subscription.pricePaidSum > 0) {
            InfoRow(
                label = stringResource(R.string.subscription_price_paid),
                value = MoneyFormatter.withCurrency(
                    subscription.pricePaidSum,
                    stringResource(R.string.currency_uzs),
                ),
            )
        }

        subscription.expiresAt?.let { expiresAt ->
            InfoRow(
                label = stringResource(R.string.subscription_expires),
                value = DateTimeFormatters.date(expiresAt),
            )
        }

        // Остаток дней считает сервер: у него есть грейс-период, и свой расчёт
        // от даты разошёлся бы с ним в самый неудобный момент.
        subscription.daysRemaining?.let { days ->
            Text(
                text = pluralStringResource(
                    R.plurals.subscription_days_left,
                    days.toInt(),
                    days,
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = colors.fgMuted,
            )
        }

        if (subscription.inGracePeriod) {
            Text(
                text = stringResource(R.string.subscription_grace),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.warning,
            )
        }

        if (subscription.canToggleAutoRenew) {
            MahallaSwitchRow(
                title = stringResource(R.string.subscription_auto_renew),
                checked = subscription.autoRenew,
                onCheckedChange = { onEvent(SubscriptionEvent.AutoRenewToggled(it)) },
                description = stringResource(R.string.subscription_auto_renew_description),
                // Пока идёт любой запрос, переключатель занят: второй тап
                // заводил бы второй переворот флага, и результат зависел бы от
                // порядка ответов.
                enabled = !state.isBusy,
            )
        }

        if (subscription.canCancel) {
            MahallaButton(
                text = stringResource(R.string.subscription_cancel),
                onClick = { onEvent(SubscriptionEvent.CancelRequested) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(
                    enabled = !state.isBusy || state.pending == SubscriptionAction.Cancel,
                    loading = state.pending == SubscriptionAction.Cancel,
                ),
            )
        }
    }
}

private fun LazyListScope.planItems(
    state: SubscriptionState,
    uzbek: Boolean,
    onEvent: (SubscriptionEvent) -> Unit,
) {
    when (val plans = state.plans) {
        is ScreenState.Loading -> item(key = "plans-loading") {
            ListSkeleton(itemCount = PLAN_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "plans-empty") {
            EmptyState(
                title = stringResource(R.string.subscription_plans_empty_title),
                description = stringResource(R.string.subscription_plans_empty_description),
                icon = Icons.Outlined.WorkspacePremium,
            )
        }

        is ScreenState.Error -> item(key = "plans-error") {
            InlineFailure(
                failure = plans.failure,
                onRetry = { onEvent(SubscriptionEvent.Retry) },
            )
        }

        is ScreenState.Content -> items(plans.data, key = SubscriptionPlan::code) { plan ->
            PlanCard(plan = plan, state = state, uzbek = uzbek, onEvent = onEvent)
        }
    }
}

@Composable
private fun PeriodSelector(
    period: BillingPeriod,
    enabled: Boolean,
    onSelect: (BillingPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = BillingPeriod.entries
    MahallaSegmentedControl(
        options = options.map { stringResource(it.labelRes()) },
        selectedIndex = options.indexOf(period),
        onSelect = { index -> onSelect(options[index]) },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * Карточка тарифа: цена за выбранный период, что в него входит и что с ним
 * можно сделать.
 */
@Composable
private fun PlanCard(
    plan: SubscriptionPlan,
    state: SubscriptionState,
    uzbek: Boolean,
    onEvent: (SubscriptionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    val subscription = state.subscription
    val isCurrentPlan = subscription != null && plan.isSameCode(subscription.planCode)
    // «Ваш тариф» — только когда совпадает и период: у той же подписки,
    // оплаченной помесячно, переход на год остаётся осмысленным действием.
    val isCurrentExactly = isCurrentPlan && subscription?.billingPeriod == state.period

    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = plan.displayName(uzbek),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isCurrentPlan) {
                MahallaBadge(
                    text = stringResource(R.string.subscription_current_plan),
                    tone = MahallaTone.Success,
                )
            } else if (plan.isPopular) {
                MahallaBadge(
                    text = stringResource(R.string.subscription_popular),
                    tone = MahallaTone.Accent,
                )
            }
        }

        Text(
            text = plan.priceLabel(state.period),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.titleLarge.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Выгода показывается только там, где она есть и где её можно
        // получить, — на годовой оплате.
        if (state.period == BillingPeriod.Yearly && plan.savingsPercent > 0) {
            Row(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = stringResource(R.string.subscription_savings, plan.savingsPercent),
                    tone = MahallaTone.Success,
                )
            }
        }

        plan.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        plan.featureLabels().forEach { label -> FeatureRow(text = label) }

        // Кнопки. У бесплатного тарифа их нет: оформлять нечего — это то, что
        // человек и так получает без подписки.
        if (plan.isPaid && !isCurrentExactly) {
            MahallaButton(
                text = stringResource(
                    if (isCurrentPlan) R.string.subscription_change_period else R.string.subscription_subscribe,
                ),
                onClick = { onEvent(SubscriptionEvent.SubscribeClicked(plan.code)) },
                modifier = Modifier.padding(top = Spacing.gap),
                state = ButtonState(
                    enabled = !state.isBusy || state.isSubscribing(plan.code),
                    loading = state.isSubscribing(plan.code),
                ),
            )
        }

        // Пробный период — только тому, у кого подписки ещё нет: второй раз
        // бэкенд его не даст, а кнопка, всегда кончающаяся отказом, хуже её
        // отсутствия.
        if (plan.hasTrial && state.trialAvailable) {
            MahallaButton(
                text = pluralStringResource(
                    R.plurals.subscription_trial_days,
                    plan.trialDays,
                    plan.trialDays,
                ),
                onClick = { onEvent(SubscriptionEvent.TrialClicked(plan.code)) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Secondary,
                state = ButtonState(
                    enabled = !state.isBusy || state.isStartingTrial(plan.code),
                    loading = state.isStartingTrial(plan.code),
                ),
            )
        }
    }
}

/** Цена за выбранный период; бесплатный тариф так и называется. */
@Composable
private fun SubscriptionPlan.priceLabel(period: BillingPeriod): String {
    if (!isPaid) return stringResource(R.string.subscription_free)
    val amount = MoneyFormatter.withCurrency(
        priceSum(period),
        stringResource(R.string.currency_uzs),
    )
    return stringResource(
        when (period) {
            BillingPeriod.Monthly -> R.string.subscription_price_monthly
            BillingPeriod.Yearly -> R.string.subscription_price_yearly
        },
        amount,
    )
}

/**
 * Что входит в тариф: сначала флаги (порядок — из `PlanFeature`, он одинаков у
 * всех тарифов, иначе их не сравнить), потом числовые лимиты.
 */
@Composable
private fun SubscriptionPlan.featureLabels(): List<String> = buildList {
    features.sortedBy(PlanFeature::ordinal).forEach { add(stringResource(it.labelRes())) }
    maxPlaces?.let { add(pluralStringResource(R.plurals.subscription_limit_places, it, it)) }
    maxListings?.let { add(pluralStringResource(R.plurals.subscription_limit_listings, it, it)) }
    maxPhotosPerListing?.let {
        add(pluralStringResource(R.plurals.subscription_limit_photos, it, it))
    }
    freePromotionsMonthly?.let {
        add(pluralStringResource(R.plurals.subscription_limit_promotions, it, it))
    }
    analyticsLevel?.let { add(stringResource(R.string.subscription_analytics, it)) }
}

@Composable
private fun FeatureRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.item),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            // Галочка дублирует смысл строки — для TalkBack она пустая.
            contentDescription = null,
            modifier = Modifier.size(FEATURE_ICON),
            tint = LocalMahallaColors.current.success,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.item),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NoticeCard(
    notice: SubscriptionNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        Text(
            text = stringResource(notice.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MahallaButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss,
            modifier = Modifier.padding(top = Spacing.item),
            variant = MahallaButtonVariant.Ghost,
            fillWidth = false,
        )
    }
}

/**
 * Отказ внутри списка: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он прокручивается сам (см. [planItems]).
 */
@Composable
private fun InlineFailure(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        if (onRetry != null) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

/**
 * Подписи домена. Домен про Android не знает, поэтому сопоставление живёт
 * здесь — как у статусов заведения в «Моих заведениях» (issue #94).
 */
@StringRes
private fun SubscriptionStatus.labelRes(): Int = when (this) {
    SubscriptionStatus.Active -> R.string.subscription_status_active
    SubscriptionStatus.Expired -> R.string.subscription_status_expired
    SubscriptionStatus.Cancelled -> R.string.subscription_status_cancelled
    SubscriptionStatus.Unknown -> R.string.subscription_status_unknown
}

/** Незнакомый статус — нейтральный тон: пугать плашкой из-за него незачем. */
private fun SubscriptionStatus.tone(): MahallaTone = when (this) {
    SubscriptionStatus.Active -> MahallaTone.Success
    SubscriptionStatus.Expired -> MahallaTone.Warning
    SubscriptionStatus.Cancelled -> MahallaTone.Error
    SubscriptionStatus.Unknown -> MahallaTone.Neutral
}

@StringRes
private fun BillingPeriod.labelRes(): Int = when (this) {
    BillingPeriod.Monthly -> R.string.subscription_period_monthly
    BillingPeriod.Yearly -> R.string.subscription_period_yearly
}

@StringRes
private fun PlanFeature.labelRes(): Int = when (this) {
    PlanFeature.NoAds -> R.string.subscription_feature_no_ads
    PlanFeature.VerifiedBadge -> R.string.subscription_feature_verified_badge
    PlanFeature.FeaturedListing -> R.string.subscription_feature_featured_listing
    PlanFeature.PrioritySupport -> R.string.subscription_feature_priority_support
    PlanFeature.MultiStaff -> R.string.subscription_feature_multi_staff
    PlanFeature.CustomBranding -> R.string.subscription_feature_custom_branding
    PlanFeature.ApiAccess -> R.string.subscription_feature_api_access
}

@StringRes
private fun SubscriptionNotice.labelRes(): Int = when (this) {
    SubscriptionNotice.Subscribed -> R.string.subscription_notice_subscribed
    SubscriptionNotice.TrialStarted -> R.string.subscription_notice_trial
    SubscriptionNotice.Cancelled -> R.string.subscription_notice_cancelled
}

/** Значение `plan_name_language` в `values/`: язык по умолчанию — узбекский. */
private const val UZBEK_TAG = "uz"

private const val PLAN_SKELETONS = 3
private val FEATURE_ICON = 18.dp

@ThemeLanguagePreviews
@Composable
private fun SubscriptionScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        SubscriptionContentScreen(
            state = SubscriptionState(
                current = ScreenState.Content(
                    Subscription(
                        planCode = "PRO",
                        planName = "Pro",
                        status = SubscriptionStatus.Active,
                        billingPeriod = BillingPeriod.Monthly,
                        pricePaidSum = 49_000,
                        expiresAt = Instant.parse("2026-10-04T09:00:00Z"),
                        autoRenew = true,
                        daysRemaining = 21,
                        isActive = true,
                    ),
                ),
                plans = ScreenState.Content(
                    listOf(
                        SubscriptionPlan(
                            code = "FREE",
                            name = "Бесплатный",
                            nameUz = "Bepul",
                            description = "Barcha asosiy imkoniyatlar",
                            isFree = true,
                        ),
                        SubscriptionPlan(
                            code = "PRO",
                            name = "Pro",
                            nameUz = "Pro",
                            description = "Reklamasiz va tasdiqlangan belgi bilan",
                            monthlySum = 49_000,
                            yearlySum = 470_000,
                            yearlyDiscountPercent = 20,
                            trialDays = 7,
                            isPopular = true,
                            features = setOf(PlanFeature.NoAds, PlanFeature.VerifiedBadge),
                            maxPlaces = 3,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
