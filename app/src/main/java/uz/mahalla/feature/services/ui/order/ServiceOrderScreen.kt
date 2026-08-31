package uz.mahalla.feature.services.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.services.domain.ServiceOrderError
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceRequest
import uz.mahalla.feature.services.domain.ServiceRequestStatus
import uz.mahalla.feature.services.ui.ServiceFormError
import uz.mahalla.ui.theme.Spacing

/**
 * Форма заказа услуги (issue #71) — клиентская половина.
 *
 * Открывается с карточки заведения действием «Встать в очередь»: человек
 * называет себя и нужную услугу, заявка уходит мастеру
 * (`POST walkin/send`), а экран после отправки показывает её состояние —
 * принял ли мастер, какое место в очереди, сколько ждать.
 */
@Composable
fun ServiceOrderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServiceOrderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ServiceOrderEffect.NavigateBack -> onBack()
            }
        }
    }

    ServiceOrderContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun ServiceOrderContent(
    state: ServiceOrderState,
    onEvent: (ServiceOrderEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.service_order_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            if (state.placeName.isNotBlank()) {
                Text(
                    text = state.placeName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            val request = state.request
            if (request == null) {
                ServiceOrderFormBlock(state = state, onEvent = onEvent)
            } else {
                ServiceRequestResult(request = request, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun ServiceOrderFormBlock(
    state: ServiceOrderState,
    onEvent: (ServiceOrderEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(
            text = stringResource(R.string.service_order_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MahallaTextField(
            value = state.form.customerName,
            onValueChange = { onEvent(ServiceOrderEvent.NameChanged(it)) },
            label = stringResource(R.string.service_order_name),
            placeholder = stringResource(R.string.service_order_name_placeholder),
            errorText = state.error { it is ServiceOrderError.NameRequired }
                ?.let { stringResource(R.string.service_order_error_name) }
                ?: state.error { it is ServiceOrderError.NameTooLong }?.tooLongText(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        MahallaTextField(
            value = state.form.serviceName,
            onValueChange = { onEvent(ServiceOrderEvent.ServiceChanged(it)) },
            label = stringResource(R.string.service_order_service),
            placeholder = stringResource(R.string.service_order_service_placeholder),
            errorText = state.error { it is ServiceOrderError.ServiceRequired }
                ?.let { stringResource(R.string.service_order_error_service) }
                ?: state.error { it is ServiceOrderError.ServiceTooLong }?.tooLongText(),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        MahallaButton(
            text = stringResource(R.string.service_order_submit),
            onClick = { onEvent(ServiceOrderEvent.SubmitClicked) },
            state = ButtonState(loading = state.isSubmitting),
        )

        state.submitFailure?.let { ServiceFormError(failure = it) }
    }
}

/**
 * Что стало с заявкой. Экран не говорит «готово»: мастер ещё может отказать
 * или предложить другое время, и человеку важнее статус, чем факт отправки.
 */
@Composable
private fun ServiceRequestResult(
    request: ServiceRequest,
    onEvent: (ServiceOrderEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        MahallaCard {
            Text(
                text = stringResource(R.string.service_order_sent_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(request.status.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            request.serviceName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            request.queuePosition?.let {
                Text(
                    text = stringResource(R.string.service_order_queue_position, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            request.estimatedWaitMinutes?.let {
                Text(
                    text = pluralStringResource(R.plurals.service_order_wait, it, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            request.counterTime?.let {
                Text(
                    text = stringResource(R.string.service_order_counter_time, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            request.barberNote?.let {
                Text(
                    text = stringResource(R.string.service_order_note, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Кнопка нужна только там, где заявке уже ничем не помочь: пока
        // мастер думает, второй такой же заявкой ему мешать незачем.
        if (request.status.isRejected) {
            MahallaButton(
                text = stringResource(R.string.service_order_new),
                onClick = { onEvent(ServiceOrderEvent.NewOrderRequested) },
                variant = MahallaButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun ServiceOrderError.tooLongText(): String = when (this) {
    is ServiceOrderError.NameTooLong ->
        stringResource(R.string.service_order_error_too_long, maxLength)

    is ServiceOrderError.ServiceTooLong ->
        stringResource(R.string.service_order_error_too_long, maxLength)

    else -> ""
}

private fun ServiceRequestStatus.labelRes(): Int = when (this) {
    ServiceRequestStatus.Pending -> R.string.service_order_status_pending
    ServiceRequestStatus.Accepted -> R.string.service_order_status_accepted
    ServiceRequestStatus.Declined -> R.string.service_order_status_declined
    ServiceRequestStatus.CounterOffered -> R.string.service_order_status_counter_offered
    ServiceRequestStatus.Waiting -> R.string.service_order_status_waiting
    ServiceRequestStatus.InChair -> R.string.service_order_status_in_chair
    ServiceRequestStatus.Completed -> R.string.service_order_status_completed
    ServiceRequestStatus.Cancelled -> R.string.service_order_status_cancelled
    ServiceRequestStatus.NoShow -> R.string.service_order_status_no_show
    ServiceRequestStatus.Expired -> R.string.service_order_status_expired
    ServiceRequestStatus.Unknown -> R.string.service_order_status_unknown
}

@ThemeLanguagePreviews
@Composable
private fun ServiceOrderFormPreview() {
    PreviewSurface {
        ServiceOrderContent(
            state = ServiceOrderState(
                placeName = "Barbershop Chilonzor",
                form = ServiceOrderForm(customerName = "Jahongir", serviceName = "Soch olish"),
                errors = emptyList(),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun ServiceOrderResultPreview() {
    PreviewSurface {
        ServiceOrderContent(
            state = ServiceOrderState(
                placeName = "Barbershop Chilonzor",
                request = ServiceRequest(
                    id = "r-1",
                    serviceName = "Soch olish",
                    status = ServiceRequestStatus.Waiting,
                    queuePosition = 3,
                    estimatedWaitMinutes = 25,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
