package uz.mahalla.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

@Immutable
data class PlaceCardUi(
    val id: String,
    val title: String,
    val category: String,
    val ratingLabel: String? = null,
    val distanceLabel: String? = null,
    val priceLabel: String? = null,
    val isOpen: Boolean = true,
    /** Логотип или фото заведения (issue #60); `null` — карточка без картинки. */
    val photoUrl: String? = null,
)

@Immutable
data class OrderCardUi(
    val id: String,
    val title: String,
    val statusLabel: String,
    val statusTone: MahallaTone,
    val amountLabel: String,
    val timeLabel: String,
)

@Immutable
data class TicketCardUi(
    val ticketNumber: String,
    val placeName: String,
    val aheadLabel: String,
    val isCalled: Boolean = false,
)

@Immutable
data class BookingCardUi(
    val placeName: String,
    val dateLabel: String,
    val timeLabel: String,
    val guestsLabel: String,
    val statusLabel: String,
    val statusTone: MahallaTone = MahallaTone.Info,
)

/** Карточка места в выдаче: название, категория, рейтинг, расстояние, статус. */
@Composable
fun PlaceCard(
    place: PlaceCardUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(if (place.isOpen) R.string.place_open_now else R.string.place_closed_now)
    val ratingDescription = place.ratingLabel?.let { stringResource(R.string.place_rating_description, it) }
    MahallaCard(onClick = onClick, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.Top,
        ) {
            // Картинка декоративная: название заведения стоит рядом, и
            // TalkBack не должен читать его дважды.
            MahallaThumbnail(url = place.photoUrl, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = place.category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
            MahallaBadge(
                text = openLabel,
                tone = if (place.isOpen) MahallaTone.Success else MahallaTone.Neutral,
            )
        }
        Spacer(modifier = Modifier.size(Spacing.item))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (place.ratingLabel != null) {
                CardMeta(
                    icon = Icons.Outlined.Star,
                    text = place.ratingLabel,
                    contentDescription = ratingDescription,
                )
            }
            if (place.distanceLabel != null) {
                CardMeta(icon = Icons.Outlined.LocationOn, text = place.distanceLabel)
            }
            if (place.priceLabel != null) {
                Text(
                    text = place.priceLabel,
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }
    }
}

/** Карточка заказа: статус тоном + текстом, сумма моноширинными цифрами. */
@Composable
fun OrderCard(
    order: OrderCardUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(onClick = onClick, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = order.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MahallaBadge(text = order.statusLabel, tone = order.statusTone)
        }
        Spacer(modifier = Modifier.size(Spacing.item))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardMeta(icon = Icons.Outlined.Schedule, text = order.timeLabel)
            Text(
                text = order.amountLabel,
                style = MaterialTheme.typography.titleSmall.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Талон очереди: номер крупно и tabular — он обновляется на месте. */
@Composable
fun TicketCard(
    ticket: TicketCardUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val numberDescription = stringResource(R.string.ticket_number_description, ticket.ticketNumber)
    MahallaCard(onClick = onClick, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ticket.placeName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
                Text(
                    text = ticket.ticketNumber,
                    modifier = Modifier.semantics { contentDescription = numberDescription },
                    style = MaterialTheme.typography.displaySmall.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MahallaBadge(
                text = ticket.aheadLabel,
                tone = if (ticket.isCalled) MahallaTone.Success else MahallaTone.Info,
            )
        }
    }
}

/** Карточка брони: дата, время, гости, статус. */
@Composable
fun BookingCard(
    booking: BookingCardUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    MahallaCard(onClick = onClick, modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = booking.placeName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MahallaBadge(text = booking.statusLabel, tone = booking.statusTone)
        }
        Spacer(modifier = Modifier.size(Spacing.item))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            CardMeta(icon = Icons.Outlined.Schedule, text = "${booking.dateLabel} · ${booking.timeLabel}")
            Text(
                text = booking.guestsLabel,
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

/**
 * Общая карточка кита: радиус, фон, рамка и внутренние отступы в одном месте.
 * `mergeDescendants` — карточка озвучивается как один объект, а не как пять
 * отдельных строк.
 */
@Composable
fun MahallaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // Тень не используем — глубина в макете задаётся фоном и рамкой.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.card), content = content)
    }
}

/**
 * Строка списка: заголовок, подпись, ведущая иконка и стрелка. Высота не
 * меньше 56dp — попадание пальцем важнее плотности (2.4).
 */
@Composable
fun MahallaListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.listItemMinHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.card, vertical = Spacing.item),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                tint = LocalMahallaColors.current.accent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        if (showChevron && onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                tint = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

/**
 * Заголовок секции с необязательным действием справа. Помечен `heading()` —
 * TalkBack умеет прыгать по заголовкам, и это единственный способ быстро
 * пройти длинный экран.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.minTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (actionLabel != null && onAction != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = MahallaComponentDefaults.minTouchTarget)
                    .clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(horizontal = Spacing.item),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalMahallaColors.current.accent,
                )
            }
        }
    }
}

@Composable
private fun CardMeta(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
            tint = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaCardsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            SectionHeader(
                title = stringResource(R.string.discovery_title),
                actionLabel = stringResource(R.string.action_see_all),
                onAction = {},
            )
            PlaceCard(
                place = PlaceCardUi(
                    id = "1",
                    title = "Choyxona Registon",
                    category = stringResource(R.string.category_food),
                    ratingLabel = "4,8",
                    distanceLabel = "450 m",
                    priceLabel = "25 000",
                ),
                onClick = {},
            )
            OrderCard(
                order = OrderCardUi(
                    id = "2",
                    title = "Dorixona №7",
                    statusLabel = stringResource(R.string.order_status_pending),
                    statusTone = MahallaTone.Warning,
                    amountLabel = "48 000",
                    timeLabel = "12:40",
                ),
                onClick = {},
            )
            TicketCard(
                ticket = TicketCardUi(
                    ticketNumber = "A-042",
                    placeName = "Poliklinika №3",
                    aheadLabel = "3",
                ),
            )
            BookingCard(
                booking = BookingCardUi(
                    placeName = "Cinema Park",
                    dateLabel = "25.08",
                    timeLabel = "19:30",
                    guestsLabel = "2",
                    statusLabel = stringResource(R.string.order_status_confirmed),
                    statusTone = MahallaTone.Success,
                ),
            )
            MahallaListItem(
                title = stringResource(R.string.profile_language),
                subtitle = stringResource(R.string.language_uz),
                onClick = {},
            )
        }
    }
}
