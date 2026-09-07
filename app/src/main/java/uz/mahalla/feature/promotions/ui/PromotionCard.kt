package uz.mahalla.feature.promotions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.promotions.domain.PromoType
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Карточка акции (issue #104): один вид и на главной, и на карточке места.
 *
 * Нажимается только то, у чего есть последствие ([Promotion.isTappable]):
 * акция платформы без заведения никуда не ведёт, и притворяться кликабельной
 * ей нельзя — нажатие без последствий читается как сломанный экран (то же
 * правило, что у уведомлений в issue #81).
 */
@Composable
fun PromotionCard(
    promotion: Promotion,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(
        modifier = modifier,
        onClick = if (promotion.isTappable) onClick else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = promotion.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            promotion.discountLabel()?.let { label ->
                MahallaBadge(text = label, tone = MahallaTone.Success)
            }
        }
        promotion.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier.padding(top = Spacing.item),
            verticalArrangement = Arrangement.spacedBy(Spacing.item / 2),
        ) {
            promotion.minOrderAmount?.let { amount ->
                Text(
                    text = stringResource(R.string.promo_min_order, money(amount)),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                    color = colors.fgMuted,
                )
            }
            // Код показываем как есть: применить его в заказе приложение пока
            // не умеет (поля под промокод в теле заказа нет, issue #9), но у
            // кассы его называет человек, и прятать его незачем.
            promotion.promoCode?.let { code ->
                Text(
                    text = stringResource(R.string.promo_code, code),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgMuted,
                )
            }
            promotion.endsAt?.let { endsAt ->
                Text(
                    text = stringResource(R.string.promo_until, DateTimeFormatters.date(endsAt)),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                    color = colors.fgMuted,
                )
            }
        }
    }
}

/**
 * Ярлык скидки. Процент — первым: его указывают чаще всего, и он понятнее
 * суммы, пока корзина не собрана. Ничего из этого сервер не прислал — ярлыка
 * нет вовсе: выдумывать условия за заведение нельзя.
 */
@Composable
private fun Promotion.discountLabel(): String? = when {
    discountPercent != null -> stringResource(R.string.promo_discount_percent, discountPercent)
    discountAmount != null ->
        stringResource(R.string.promo_discount_amount, money(discountAmount))

    type == PromoType.FreeDelivery -> stringResource(R.string.promo_free_delivery)
    else -> null
}

@Composable
private fun money(sum: Long): String =
    MoneyFormatter.withCurrency(sum, stringResource(R.string.currency_uzs))

@ThemeLanguagePreviews
@Composable
private fun PromotionCardPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            PromotionCard(
                promotion = Promotion(
                    id = "promo-1",
                    title = "Osh Markazi: 20% chegirma",
                    description = "Faqat ish kunlari, soat 12:00 gacha.",
                    type = PromoType.PercentOff,
                    placeId = "p-1",
                    discountPercent = 20,
                    minOrderAmount = 50_000,
                    promoCode = "OSH20",
                    endsAt = Instant.parse("2026-09-30T18:00:00Z"),
                ),
                onClick = {},
            )
            PromotionCard(
                promotion = Promotion(
                    id = "promo-2",
                    title = "Bepul yetkazib berish",
                    type = PromoType.FreeDelivery,
                    isPlatformWide = true,
                ),
            )
        }
    }
}
