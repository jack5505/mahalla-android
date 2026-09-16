package uz.mahalla.feature.discovery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.preview.LargeFontPreviews
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.ui.distanceLabel
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import uz.mahalla.ui.theme.FocusGhostNumeral
import uz.mahalla.ui.theme.FocusGradientEnd
import uz.mahalla.ui.theme.FocusGradientStart
import uz.mahalla.ui.theme.FocusHeadline
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Фокус-карточка главной (макет 1a/1d) — то, что отвечает на вопрос «что мне
 * сейчас».
 *
 * Два состояния: пока талона нет — ближайшее открытое место; как только талон
 * взят — сам талон. Это одна карточка, а не две: в макете она физически
 * одна и та же, и второй блок под ней означал бы, что «сейчас» у человека два.
 *
 * Чего в карточке нет по сравнению с макетом и почему:
 * - **«Взять талон» на ближайшем месте.** Каталог не знает, принимает ли место
 *   очередь: возможности (`PlaceCapabilities`) приезжают отдельным запросом
 *   карточки места. Кнопка вела бы в очередь заведения, которое её не ведёт,
 *   поэтому ведём на само место — там действия уже настоящие.
 * - **«Обновить очередь».** Перечитать талон нечем: ручки `GET walkin/my` у
 *   бэкенда нет (`WalkInApi`), а кнопка, вычитающая двойку на клиенте, как в
 *   прототипе, — выдуманное движение очереди.
 */
@Composable
fun FocusCard(
    ticket: WalkInTicket?,
    queueInfoIsCurrent: Boolean,
    nearestOpenPlace: Place?,
    onOpenTicket: () -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        ticket != null -> FocusSurface(
            modifier = modifier,
            // Позиция в очереди — то самое число, которое в макете нарисовано
            // призраком на фоне. Показываем его только пока оно свежее.
            ghostNumber = ticket.queuePosition?.takeIf { queueInfoIsCurrent }?.toString(),
            kicker = stringResource(R.string.home_focus_ticket_kicker),
            title = ticket.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.queue_title),
            subtitle = ticket.focusSubtitle(queueInfoIsCurrent),
            primaryLabel = stringResource(R.string.home_focus_open_ticket),
            onPrimary = onOpenTicket,
            secondaryLabel = stringResource(R.string.home_focus_open_place),
            onSecondary = { onOpenPlace(ticket.placeId) },
        )

        nearestOpenPlace != null -> FocusSurface(
            modifier = modifier,
            ghostNumber = null,
            kicker = stringResource(R.string.home_focus_nearest_kicker),
            title = nearestOpenPlace.name,
            subtitle = stringResource(
                R.string.text_joined_with_dot,
                stringResource(nearestOpenPlace.category.labelRes),
                distanceLabel(nearestOpenPlace.distanceMeters),
            ),
            primaryLabel = stringResource(R.string.home_focus_open_place),
            onPrimary = { onOpenPlace(nearestOpenPlace.id) },
            secondaryLabel = null,
            onSecondary = {},
        )

        // Ни талона, ни открытого места рядом — карточки нет вовсе: пустой
        // градиент без ответа на «что мне сейчас» занимал бы пол-экрана зря.
        else -> Unit
    }
}

@Composable
private fun WalkInTicket.focusSubtitle(queueInfoIsCurrent: Boolean): String {
    val position = queuePosition?.takeIf { queueInfoIsCurrent }
    return if (position == null) {
        // Числа устарели или их не было — остаётся состояние талона, и оно
        // честнее замершей позиции.
        stringResource(status.focusLabelRes())
    } else {
        stringResource(R.string.home_focus_ticket_position, position)
    }
}

/**
 * Состояние талона одной строкой для карточки. Подробности (этапы, отмена,
 * комментарий мастера) живут на экране очереди — сюда они не влезают и не
 * должны: карточка отвечает «что сейчас», а не «что было».
 */
private fun WalkInStatus.focusLabelRes(): Int = when (this) {
    WalkInStatus.Pending -> R.string.queue_status_pending
    WalkInStatus.Accepted -> R.string.queue_status_accepted
    WalkInStatus.Declined -> R.string.queue_status_declined
    WalkInStatus.CounterOffered -> R.string.queue_status_counter_offered
    WalkInStatus.Waiting -> R.string.queue_status_waiting
    WalkInStatus.InChair -> R.string.queue_status_in_chair
    WalkInStatus.Completed -> R.string.queue_status_completed
    WalkInStatus.Cancelled -> R.string.queue_status_cancelled
    WalkInStatus.NoShow -> R.string.queue_status_no_show
    WalkInStatus.Expired -> R.string.queue_status_expired
    WalkInStatus.Unknown -> R.string.queue_status_unknown
}

@Composable
private fun FocusSurface(
    ghostNumber: String?,
    kicker: String,
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(FocusGradientStart, FocusGradientEnd)))
            .semantics(mergeDescendants = true) {},
    ) {
        if (ghostNumber != null) {
            // matchParentSize — чтобы 118dp цифра не задавала высоту карточки:
            // она декорация в углу, а высоту держит текстовый блок. Заодно на
            // крупном системном шрифте карточка не раздувается вслед за ней.
            Box(modifier = Modifier.matchParentSize()) {
                Text(
                    text = ghostNumber,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-30).dp)
                        // Число уже сказано подписью под заголовком: без этого
                        // mergeDescendants родителя втягивает его в озвучку, и
                        // TalkBack читает позицию дважды.
                        .clearAndSetSemantics {},
                    style = FocusGhostNumeral.merge(TabularNums),
                    color = Color.White.copy(alpha = GhostAlpha),
                )
            }
        }
        Column(modifier = Modifier.padding(Spacing.card)) {
            Text(
                text = kicker,
                style = MaterialTheme.typography.labelLarge,
                color = OnGradientMuted,
            )
            Text(
                text = title,
                // Правый отступ — чтобы заголовок не заезжал под призрачное
                // число в углу.
                modifier = Modifier
                    .padding(top = Spacing.item / 2, end = if (ghostNumber == null) 0.dp else GhostGutter),
                style = FocusHeadline,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = Spacing.item / 2),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = OnGradientMuted,
            )
            Row(
                modifier = Modifier.padding(top = Spacing.card),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                MahallaButton(
                    text = primaryLabel,
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f),
                    variant = MahallaButtonVariant.OnColor,
                )
                if (secondaryLabel != null) {
                    MahallaButton(
                        text = secondaryLabel,
                        onClick = onSecondary,
                        modifier = Modifier.weight(1f),
                        variant = MahallaButtonVariant.OnColorGhost,
                    )
                }
            }
        }
    }
}

/** Вторичный текст на градиенте — primaryContainer из макета. */
private val OnGradientMuted = Color(0xFFE8DEFF)

private const val GhostAlpha = 0.14f

/** Место под призрачное число: заголовок в него не заезжает. */
private val GhostGutter = 64.dp

@ThemeLanguagePreviews
@LargeFontPreviews
@Composable
private fun FocusCardTicketPreview() {
    PreviewSurface {
        FocusCard(
            ticket = WalkInTicket(
                id = "t-1",
                placeId = "p-1",
                placeName = "Barber House",
                status = WalkInStatus.Waiting,
                queuePosition = 3,
                estimatedWaitMinutes = 9,
                receivedAt = Instant.parse("2026-09-16T09:30:00Z"),
            ),
            queueInfoIsCurrent = true,
            nearestOpenPlace = null,
            onOpenTicket = {},
            onOpenPlace = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun FocusCardNearestPreview() {
    PreviewSurface {
        FocusCard(
            ticket = null,
            queueInfoIsCurrent = false,
            nearestOpenPlace = Place(
                id = "p-2",
                name = "Dorixona 24",
                category = PlaceCategory.Pharmacy,
                rating = 4.8,
                reviewCount = 312,
                distanceMeters = 180,
                isOpenNow = true,
            ),
            onOpenTicket = {},
            onOpenPlace = {},
        )
    }
}
