package uz.mahalla.feature.business.ui.queue

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSnackbarHost
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.rememberSnackbarController
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueActionRules
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.time.Instant

/**
 * Управление очередью (задача 12.2).
 *
 * Главная кнопка — «вызвать следующего» над списком: мастер держит машинку в
 * одной руке, и искать нужную строку в очереди из десяти человек ему некогда.
 * Всё остальное — действия по конкретному талону.
 */
@Composable
fun BusinessQueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarController()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessQueueEvent.ScreenResumed)
    }

    // Подпись собирается здесь, а не во ViewModel: строки живут в ресурсах, и
    // домен про Android не знает (правило `i18n.md`).
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BusinessQueueEffect.ActionDone -> snackbar.show(
                    text = context.getString(
                        effect.action.doneRes(),
                        effect.userName.takeIf(String::isNotBlank)
                            ?: context.getString(R.string.business_queue_unnamed),
                    ),
                    tone = if (effect.action == QueueAction.Decline) {
                        MahallaTone.Warning
                    } else {
                        MahallaTone.Success
                    },
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BusinessQueueContentScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = onBack,
        )
        MahallaSnackbarHost(
            controller = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessQueueContentScreen(
    state: BusinessQueueState,
    onEvent: (BusinessQueueEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.business_section_queue),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessQueueEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { BusinessInlineFailure(failure = failure) }
                }
                callNextItem(state = state, onEvent = onEvent)
                queueItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * «Вызвать следующего» показывается только тогда, когда вызывать есть кого.
 *
 * Пока кто-то в кресле, кнопки нет вовсе (см. `QueueActionRules.nextInLine`):
 * серая неактивная кнопка объясняла бы делом то, что понятнее сказать словами,
 * — и первая же строка списка это и говорит («в кресле»).
 */
private fun LazyListScope.callNextItem(
    state: BusinessQueueState,
    onEvent: (BusinessQueueEvent) -> Unit,
) {
    val next = state.nextInLine ?: return
    item(key = "call-next") {
        MahallaCard {
            Text(
                text = stringResource(R.string.business_queue_next_title),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            Text(
                text = next.userName.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.business_queue_unnamed),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaButton(
                text = stringResource(R.string.business_queue_call_next),
                onClick = { onEvent(BusinessQueueEvent.CallNextClicked) },
                modifier = Modifier.padding(top = Spacing.gap),
                state = ButtonState(enabled = !state.isBusy),
            )
            Text(
                text = stringResource(R.string.business_queue_waiting, state.waitingCount),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

private fun LazyListScope.queueItems(
    state: BusinessQueueState,
    onEvent: (BusinessQueueEvent) -> Unit,
) {
    when (val entries = state.entries) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = 3) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.business_queue_empty_title),
                description = stringResource(R.string.business_queue_empty_description),
                icon = Icons.Outlined.People,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            BusinessInlineFailure(
                failure = entries.failure,
                onRetry = { onEvent(BusinessQueueEvent.Retry) },
            )
        }

        is ScreenState.Content -> items(entries.data, key = QueueEntry::id) { entry ->
            QueueEntryCard(
                entry = entry,
                pending = state.pendingTicketId == entry.id,
                // Пока идёт запрос по одному талону, остальные не трогаем:
                // очередь пересчитывается целиком.
                enabled = !state.isBusy,
                onEvent = onEvent,
            )
        }
    }
}

/**
 * Талон в списке: имя, услуга, статус и доступные действия.
 *
 * Кнопок ровно столько, сколько разрешает домен: обслуженному талону кнопок не
 * положено вовсе, и серую «завершить» под надписью «обслужен» рисовать
 * незачем.
 */
@Composable
private fun QueueEntryCard(
    entry: QueueEntry,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (BusinessQueueEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.userName.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.business_queue_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(entry.status.labelRes()),
                tone = entry.status.tone(),
            )
        }

        entry.serviceName?.let { service ->
            Text(
                text = service,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        entry.queuePosition?.let { position ->
            Text(
                text = stringResource(R.string.business_queue_position, position),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        entry.createdAt?.let { created ->
            Text(
                text = stringResource(
                    R.string.business_queue_created_at,
                    DateTimeFormatters.time(created),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        entry.note?.let { note ->
            Text(
                text = note,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        val actions = QueueActionRules.available(entry.status)
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.gap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                actions.forEach { action ->
                    MahallaButton(
                        text = stringResource(action.labelRes()),
                        onClick = { onEvent(BusinessQueueEvent.ActionClicked(entry.id, action)) },
                        modifier = Modifier.weight(1f),
                        variant = action.variant(),
                        state = ButtonState(enabled = enabled, loading = pending),
                    )
                }
            }
        }
    }
}

@StringRes
private fun QueueAction.labelRes(): Int = when (this) {
    QueueAction.Accept -> R.string.business_queue_accept
    QueueAction.Decline -> R.string.business_queue_decline
    QueueAction.Start -> R.string.business_queue_start
    QueueAction.Complete -> R.string.business_queue_complete
}

/** Шаблон snackbar'а: «%1$s принят», «%1$s в кресле». */
@StringRes
private fun QueueAction.doneRes(): Int = when (this) {
    QueueAction.Accept -> R.string.business_queue_done_accept
    QueueAction.Decline -> R.string.business_queue_done_decline
    QueueAction.Start -> R.string.business_queue_done_start
    QueueAction.Complete -> R.string.business_queue_done_complete
}

/** Отказ — вторичной кнопкой: он не должен быть таким же заметным, как вызов. */
private fun QueueAction.variant(): MahallaButtonVariant = when (this) {
    QueueAction.Decline -> MahallaButtonVariant.Ghost
    QueueAction.Accept, QueueAction.Start, QueueAction.Complete -> MahallaButtonVariant.Primary
}

@StringRes
private fun WalkInStatus.labelRes(): Int = when (this) {
    WalkInStatus.Pending -> R.string.business_queue_status_pending
    WalkInStatus.Accepted -> R.string.business_queue_status_accepted
    WalkInStatus.Declined -> R.string.business_queue_status_declined
    WalkInStatus.CounterOffered -> R.string.business_queue_status_counter
    WalkInStatus.Waiting -> R.string.business_queue_status_waiting
    WalkInStatus.InChair -> R.string.business_queue_status_in_chair
    WalkInStatus.Completed -> R.string.business_queue_status_completed
    WalkInStatus.Cancelled -> R.string.business_queue_status_cancelled
    WalkInStatus.NoShow -> R.string.business_queue_status_no_show
    WalkInStatus.Expired -> R.string.business_queue_status_expired
    WalkInStatus.Unknown -> R.string.business_queue_status_unknown
}

private fun WalkInStatus.tone(): MahallaTone = when (this) {
    WalkInStatus.InChair, WalkInStatus.Completed -> MahallaTone.Success
    WalkInStatus.Pending, WalkInStatus.CounterOffered -> MahallaTone.Warning
    WalkInStatus.Declined, WalkInStatus.Cancelled, WalkInStatus.NoShow, WalkInStatus.Expired ->
        MahallaTone.Error

    WalkInStatus.Accepted, WalkInStatus.Waiting, WalkInStatus.Unknown -> MahallaTone.Neutral
}

@ThemeLanguagePreviews
@Composable
private fun BusinessQueueScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessQueueContentScreen(
            state = BusinessQueueState(
                placeName = "Barber Studio",
                entries = ScreenState.Content(
                    listOf(
                        QueueEntry(
                            id = "t-1",
                            userName = "Alisher",
                            serviceName = "Soch olish",
                            status = WalkInStatus.Waiting,
                            queuePosition = 1,
                            createdAt = Instant.parse("2026-09-09T09:15:00Z"),
                        ),
                        QueueEntry(
                            id = "t-2",
                            userName = "Bekzod",
                            status = WalkInStatus.Pending,
                            createdAt = Instant.parse("2026-09-09T09:32:00Z"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
