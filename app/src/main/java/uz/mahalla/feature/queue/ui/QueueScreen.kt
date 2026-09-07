package uz.mahalla.feature.queue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.ui.OnboardingApiError
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInRequestError
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInStatusFlow
import uz.mahalla.feature.queue.domain.WalkInTicket
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Очередь заведения (issue #96): форма записи, пока талона нет, и сам талон,
 * когда он есть.
 */
@Composable
fun QueueScreen(
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                QueueEffect.OpenNotifications -> onOpenNotifications()
                QueueEffect.NavigateBack -> onBack()
            }
        }
    }

    // Пока приложение было в фоне, очередь могла уехать: числа на экране
    // перестают выдаваться за текущие. Запроса здесь нет — читать состояние
    // талона у бэкенда нечем (см. `WalkInApi`).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(QueueEvent.ScreenResumed)
    }

    QueueContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun QueueContent(
    state: QueueState,
    onEvent: (QueueEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.queue_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            when {
                // «Талона нет» и «ещё не знаем» — разные вещи: показать форму
                // раньше времени значит предложить записаться второй раз.
                state.isLoading -> CardSkeleton()
                state.ticket != null -> TicketBlock(state = state, onEvent = onEvent)
                else -> RequestForm(state = state, onEvent = onEvent)
            }
        }
    }

    if (state.cancelConfirmVisible) {
        MahallaDialog(
            title = stringResource(R.string.queue_cancel_confirm_title),
            text = stringResource(R.string.queue_cancel_confirm_description),
            confirmLabel = stringResource(R.string.queue_cancel),
            onConfirm = { onEvent(QueueEvent.CancelConfirmed) },
            onDismiss = { onEvent(QueueEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.queue_cancel_keep),
            destructive = true,
        )
    }
}

@Composable
private fun RequestForm(
    state: QueueState,
    onEvent: (QueueEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        Text(
            text = stringResource(R.string.queue_form_description),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )

        MahallaTextField(
            value = state.request.userName,
            onValueChange = { onEvent(QueueEvent.NameChanged(it)) },
            label = stringResource(R.string.queue_field_name),
            placeholder = stringResource(R.string.queue_field_name_placeholder),
            supportingText = stringResource(R.string.queue_field_name_hint),
            errorText = state.nameErrorText(),
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )

        MahallaTextField(
            value = state.request.serviceName,
            onValueChange = { onEvent(QueueEvent.ServiceChanged(it)) },
            label = stringResource(R.string.queue_field_service),
            placeholder = stringResource(R.string.queue_field_service_placeholder),
            supportingText = stringResource(R.string.queue_field_service_hint),
            errorText = state.serviceErrorText(),
            enabled = !state.isSubmitting,
        )

        state.submitFailure?.let { OnboardingApiError(failure = it) }

        MahallaButton(
            text = stringResource(R.string.queue_submit),
            onClick = { onEvent(QueueEvent.SubmitClicked) },
            state = ButtonState(enabled = state.canSubmit, loading = state.isSubmitting),
        )
    }
}

@Composable
private fun TicketBlock(
    state: QueueState,
    onEvent: (QueueEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        MahallaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.queue_ticket_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MahallaBadge(
                    text = stringResource(ticket.status.labelRes()),
                    tone = ticket.status.tone(),
                )
            }

            ticket.serviceName?.let { service ->
                Text(
                    text = stringResource(R.string.queue_ticket_service, service),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }

            QueueNumbers(ticket = ticket, isCurrent = state.queueInfoIsCurrent)

            ticket.counterTime?.let { time ->
                Text(
                    text = stringResource(
                        R.string.queue_ticket_counter_time,
                        DateTimeFormatters.time(time),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.warning,
                )
            }

            ticket.note?.let { note ->
                Text(
                    text = stringResource(R.string.queue_ticket_note, note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Время, на которое известно состояние. Подписывать обязательно:
            // перечитать очередь нечем, и «третий в очереди» без времени
            // выглядел бы как живое число.
            Text(
                text = stringResource(
                    R.string.queue_ticket_as_of,
                    DateTimeFormatters.time(ticket.receivedAt),
                ),
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        if (WalkInStatusFlow.showsStages(ticket.status)) {
            StagesBlock(status = ticket.status)
        }

        if (WalkInStatusFlow.isActive(ticket.status)) {
            // О решении мастера сообщает бэкенд — уведомлениями `WALKIN_*`
            // (issue #81). Это единственный источник, где состояние талона
            // обновляет сам сервер, поэтому путь туда даётся кнопкой.
            OnboardingNotice(
                text = stringResource(R.string.queue_ticket_notice),
                action = {
                    MahallaButton(
                        text = stringResource(R.string.queue_open_notifications),
                        onClick = { onEvent(QueueEvent.NotificationsClicked) },
                        variant = MahallaButtonVariant.Secondary,
                    )
                },
            )
        }

        state.cancelFailure?.let { OnboardingApiError(failure = it) }

        if (state.canCancel || state.isCancelling) {
            MahallaButton(
                text = stringResource(R.string.queue_cancel),
                onClick = { onEvent(QueueEvent.CancelClicked) },
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(loading = state.isCancelling),
            )
        }
    }
}

/**
 * Позиция и ожидание. Числа показываются только пока они свежие — иначе
 * вместо них объяснение: очередь двигают чужие отмены, а перечитать её нечем.
 */
@Composable
private fun QueueNumbers(
    ticket: WalkInTicket,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    val position = ticket.queuePosition
    val wait = ticket.estimatedWaitMinutes
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        when {
            position == null && wait == null -> Text(
                text = stringResource(R.string.queue_position_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )

            !isCurrent -> Text(
                text = stringResource(R.string.queue_position_outdated),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )

            else -> {
                position?.let {
                    Text(
                        text = stringResource(R.string.queue_position_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                    Text(
                        // Моноширинные цифры: номер не должен «дёргаться».
                        text = it.toString(),
                        style = MaterialTheme.typography.headlineLarge.merge(TabularNums),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                wait?.let {
                    Text(
                        text = stringResource(R.string.queue_wait_label, waitText(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }
        }
    }
}

/** До часа — минуты, дальше `ч:мм` (`DateTimeFormatters.waitingTime`). */
@Composable
private fun waitText(minutes: Int): String = if (minutes < MINUTES_IN_HOUR) {
    pluralStringResource(R.plurals.queue_wait_minutes, minutes, minutes)
} else {
    stringResource(
        R.string.queue_wait_hours,
        DateTimeFormatters.waitingTime(minutes.toLong()),
    )
}

@Composable
private fun StagesBlock(status: WalkInStatus, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        WalkInStatusFlow.stages().forEach { stage ->
            val done = WalkInStatusFlow.isStageDone(stage, status)
            val current = stage == status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (done) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    // Состояние этапа читается из текста рядом — иконка
                    // декоративная и для TalkBack пустая.
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                    tint = when {
                        done -> LocalMahallaColors.current.success
                        current -> LocalMahallaColors.current.accent
                        else -> LocalMahallaColors.current.fgMuted
                    },
                )
                Text(
                    text = stringResource(stage.labelRes()),
                    style = if (current) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (done || current) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        LocalMahallaColors.current.fgMuted
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueState.nameErrorText(): String? {
    if (!validationShown) return null
    return errors.firstNotNullOfOrNull { error ->
        when (error) {
            WalkInRequestError.NameRequired -> stringResource(R.string.queue_error_name_required)
            is WalkInRequestError.NameTooLong ->
                pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

            else -> null
        }
    }
}

@Composable
private fun QueueState.serviceErrorText(): String? {
    if (!validationShown) return null
    val error = errors.filterIsInstance<WalkInRequestError.ServiceTooLong>().firstOrNull()
        ?: return null
    return pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)
}

/** Подпись состояния. Домен знает состояние, ресурсы — формулировку. */
private fun WalkInStatus.labelRes(): Int = when (this) {
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

private fun WalkInStatus.tone(): MahallaTone = when (this) {
    WalkInStatus.Completed -> MahallaTone.Success
    WalkInStatus.Accepted, WalkInStatus.Waiting, WalkInStatus.InChair -> MahallaTone.Info
    WalkInStatus.Declined, WalkInStatus.NoShow -> MahallaTone.Error
    // Отмена — решение самого человека, а не ошибка: красная плашка тут
    // читалась бы как «что-то сломалось».
    WalkInStatus.Cancelled, WalkInStatus.Expired -> MahallaTone.Neutral
    WalkInStatus.CounterOffered -> MahallaTone.Warning
    WalkInStatus.Pending -> MahallaTone.Info
    WalkInStatus.Unknown -> MahallaTone.Neutral
}

private const val MINUTES_IN_HOUR = 60

@ThemeLanguagePreviews
@Composable
private fun QueueFormPreview() {
    PreviewSurface {
        QueueContent(
            state = QueueState(
                placeName = "Barber House",
                request = WalkInRequest(placeId = "p-1", userName = "Jahongir"),
                isLoading = false,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun QueueTicketPreview() {
    PreviewSurface {
        QueueContent(
            state = QueueState(
                placeName = "Barber House",
                isLoading = false,
                queueInfoIsCurrent = true,
                ticket = WalkInTicket(
                    id = "t-1",
                    placeId = "p-1",
                    placeName = "Barber House",
                    userName = "Jahongir",
                    serviceName = "Soch olish",
                    status = WalkInStatus.Waiting,
                    queuePosition = 3,
                    estimatedWaitMinutes = 25,
                    receivedAt = Instant.parse("2026-09-04T09:30:00Z"),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
