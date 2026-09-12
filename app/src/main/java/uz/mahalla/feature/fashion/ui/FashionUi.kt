package uz.mahalla.feature.fashion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.ui.theme.Spacing

/**
 * Общее для экранов вертикали «Одежда» (issue #108): отказ и подписи статусов.
 * Пять экранов показывают одно и то же — четыре копии разошлись бы при первой
 * правке. Хвост списка — общий для всего приложения `LoadMoreAuto` (issue
 * #214), не здесь.
 */

/**
 * Отказ с текстом сервера и раскрывающимися подробностями (issue #34).
 * Рисуется **над** содержимым, а не вместо него: список уже на экране, и
 * прятать его из-за неудавшейся кнопки незачем.
 */
@Composable
fun FashionFailure(
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

/** Цена в сумах — одинаково на витрине, в карточке, в корзине и в заказе. */
@Composable
fun priceText(sum: Long): String =
    MoneyFormatter.withCurrency(sum, stringResource(R.string.currency_uzs))

/** Подписи статусов заказа: домен знает состояние, ресурсы — формулировку. */
@StringRes
fun OrderStatus.labelRes(): Int = when (this) {
    OrderStatus.Created -> R.string.order_status_created
    OrderStatus.Confirmed -> R.string.order_status_confirmed_food
    OrderStatus.Preparing -> R.string.order_status_preparing
    OrderStatus.ReadyForPickup -> R.string.order_status_ready
    OrderStatus.Delivering -> R.string.order_status_delivering
    OrderStatus.Completed -> R.string.order_status_completed
    OrderStatus.Cancelled -> R.string.order_status_cancelled
    OrderStatus.Refunded -> R.string.order_status_refunded
    OrderStatus.Unknown -> R.string.order_status_unknown
}

/** Отмена — решение человека, а не сбой: красная плашка читалась бы иначе. */
fun OrderStatus.tone(): MahallaTone = when (this) {
    OrderStatus.Completed -> MahallaTone.Success
    OrderStatus.Cancelled -> MahallaTone.Neutral
    OrderStatus.Refunded -> MahallaTone.Warning
    OrderStatus.Unknown -> MahallaTone.Neutral
    else -> MahallaTone.Info
}
