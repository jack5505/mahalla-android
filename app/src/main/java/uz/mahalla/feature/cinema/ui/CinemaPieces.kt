package uz.mahalla.feature.cinema.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.userMessage
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Общие куски экранов кино (issue #106): афиша, карточка фильма и билеты
 * рисуют одно и то же — постер, отказ, подписи статусов, — и три копии
 * разошлись бы при первой правке.
 */

/**
 * Узбекский ли сейчас интерфейс. Нужно ровно для выбора названия фильма
 * (`title` против `titleUz`): язык приложение выбирает своё (эпик 1.5), и
 * per-app locale доезжает сюда обычной локалью конфигурации.
 */
@Composable
fun prefersUzbekTitle(): Boolean = Locale.current.language.equals("uz", ignoreCase = true)

/**
 * Место под постер.
 *
 * Картинки не будет, пока в проекте нет загрузчика изображений: `posterUrl`
 * доезжает до домена и подставится сюда без изменений экрана. Пока — плашка с
 * иконкой, а не пустота: без неё карточка фильма выглядит как недогруженная.
 */
@Composable
fun MoviePoster(
    modifier: Modifier = Modifier,
    width: Int = POSTER_WIDTH,
    height: Int = POSTER_HEIGHT,
) {
    Box(
        modifier = modifier
            .width(width.dp)
            .height(height.dp)
            .clip(RoundedCornerShape(Spacing.item))
            .background(LocalMahallaColors.current.skeleton),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            modifier = Modifier.size(POSTER_ICON.dp),
            tint = LocalMahallaColors.current.fgMuted,
        )
    }
}

/**
 * Отказ с текстом сервера и раскрывающимися подробностями (issue #34).
 *
 * Своя копия, а не `InlineFailure` из брони: тот живёт в
 * `feature/booking/ui`, и тянуть вертикаль записи в кино ради четырёх строк
 * значило бы связать их между собой навсегда.
 */
@Composable
fun CinemaFailure(
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

/** Подписи статусов: домен знает состояние, ресурсы — формулировку. */
@StringRes
fun CinemaTicketStatus.labelRes(): Int = when (this) {
    CinemaTicketStatus.Active -> R.string.cinema_ticket_status_active
    CinemaTicketStatus.Used -> R.string.cinema_ticket_status_used
    CinemaTicketStatus.Cancelled -> R.string.cinema_ticket_status_cancelled
    CinemaTicketStatus.Refunded -> R.string.cinema_ticket_status_refunded
    CinemaTicketStatus.Unknown -> R.string.cinema_ticket_status_unknown
}

/**
 * Возврат — решение человека, а не сбой: красная плашка читалась бы иначе.
 * Использованный билет — обычное завершение, тоже нейтральный.
 */
fun CinemaTicketStatus.tone(): MahallaTone = when (this) {
    CinemaTicketStatus.Active -> MahallaTone.Success
    CinemaTicketStatus.Used -> MahallaTone.Neutral
    CinemaTicketStatus.Cancelled -> MahallaTone.Neutral
    CinemaTicketStatus.Refunded -> MahallaTone.Info
    CinemaTicketStatus.Unknown -> MahallaTone.Neutral
}

private const val POSTER_WIDTH = 72
private const val POSTER_HEIGHT = 104
private const val POSTER_ICON = 28
