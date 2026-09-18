package uz.mahalla.feature.wallet.ui.pay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.biometric.findFragmentActivity
import uz.mahalla.core.ui.biometric.showBiometricPrompt
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaOtpField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.feature.wallet.domain.WalletPaymentRejection
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Подтверждение оплаты из кошелька (задача 8.3 эпика #12) — одна шторка на все
 * вертикали.
 *
 * Шторка, а не экран: подтверждают уже собранный заказ, и итог с составом
 * должны остаться видны за ней — иначе человек подтверждает сумму, которую
 * больше не с чем сверить.
 *
 * Сумма всегда на месте, на каком бы шаге ни была оплата: и в отказе «не
 * хватает», и в ожидании ответа сервера главный вопрос один — сколько списывают.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentConfirmSheet(
    state: WalletPaymentState,
    onPinChanged: (String) -> Unit,
    onBiometricConfirmed: () -> Unit,
    onBiometricRejected: () -> Unit,
    onRetry: () -> Unit,
    onTopUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Промпт показывается сам, как только оплата дошла до подтверждения:
    // отдельная кнопка «приложить палец» была бы вторым нажатием ни за что.
    if (state.method == PaymentConfirmationMethod.Biometric &&
        state.step == WalletPaymentStep.Confirm
    ) {
        BiometricConfirmation(
            amountSum = state.amountSum,
            onConfirmed = onBiometricConfirmed,
            onRejected = onBiometricRejected,
        )
    }

    MahallaBottomSheet(
        onDismiss = {
            // Пока запрос в полёте, шторка не закрывается: закрыть её значит
            // спрятать от человека, ушли деньги или нет.
            if (state.step != WalletPaymentStep.Submitting) onDismiss()
        },
        modifier = modifier,
        title = stringResource(R.string.payment_confirm_title),
    ) {
        PaymentAmount(state = state)

        when {
            state.rejection != null -> PaymentRejection(
                state = state,
                onRetry = onRetry,
                onTopUp = onTopUp,
                onDismiss = onDismiss,
            )

            state.isPinStep || state.step == WalletPaymentStep.Checking -> PinConfirmation(
                state = state,
                onPinChanged = onPinChanged,
            )

            else -> PaymentProgress(state = state)
        }
    }
}

@Composable
private fun PaymentAmount(state: WalletPaymentState, modifier: Modifier = Modifier) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.payment_confirm_amount),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            Text(
                text = MoneyFormatter.withCurrency(state.amountSum, currency),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
            )
        }
        // Баланс мог не приехать — тогда о нём молчим: «доступно 0» было бы
        // неправдой, а оплата всё равно идёт, решает сервер.
        state.availableSum?.let { available ->
            Text(
                text = stringResource(
                    R.string.payment_confirm_available,
                    MoneyFormatter.withCurrency(available, currency),
                ),
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

@Composable
private fun PinConfirmation(
    state: WalletPaymentState,
    onPinChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Клавиатура открывается сама: шторка и появилась ради ввода кода.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        Text(
            text = stringResource(R.string.payment_confirm_pin_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MahallaOtpField(
            state = state.pin,
            onCodeChange = onPinChanged,
            enabled = !state.isBusy,
            // Осталось попыток — не пугалка, а способ решить: вводить ещё раз
            // или закрыть и вспомнить код спокойно.
            errorText = if (state.pin.isError) {
                pluralStringResource(
                    R.plurals.payment_confirm_pin_wrong,
                    state.attemptsLeft,
                    state.attemptsLeft,
                )
            } else {
                null
            },
            masked = true,
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun PaymentProgress(state: WalletPaymentState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(PROGRESS_SIZE),
            strokeWidth = PROGRESS_STROKE,
        )
        Text(
            text = stringResource(
                if (state.step == WalletPaymentStep.Submitting) {
                    R.string.payment_confirm_submitting
                } else {
                    R.string.payment_confirm_preparing
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

/**
 * Отказ. У каждой причины своё действие: не хватает денег — пополнить, отказал
 * сервер — повторить тем же ключом, заблокирован кошелёк или исчерпаны попытки
 * — только закрыть, потому что делать здесь больше нечего.
 */
@Composable
private fun PaymentRejection(
    state: WalletPaymentState,
    onRetry: () -> Unit,
    onTopUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    val rejection = state.rejection ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        Text(
            text = when (rejection) {
                is WalletPaymentRejection.InsufficientFunds -> rejection.missingSum
                    ?.let {
                        stringResource(
                            R.string.payment_confirm_insufficient_missing,
                            MoneyFormatter.withCurrency(it, currency),
                        )
                    }
                    ?: stringResource(R.string.payment_confirm_insufficient)

                WalletPaymentRejection.WalletBlocked ->
                    stringResource(R.string.payment_confirm_blocked)

                WalletPaymentRejection.ConfirmationFailed ->
                    stringResource(R.string.payment_confirm_failed)

                // Текст бэкенда точнее нашего (issue #34): «позиция уехала в
                // стоп-лист» объясняет отказ, а «что-то не так» — нет.
                is WalletPaymentRejection.Declined -> rejection.failure.userMessage()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        (rejection as? WalletPaymentRejection.Declined)?.failure?.server?.let {
            MahallaErrorDetails(server = it)
        }

        if (rejection is WalletPaymentRejection.InsufficientFunds) {
            MahallaButton(
                text = stringResource(R.string.checkout_top_up),
                onClick = onTopUp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.canRetry) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        MahallaButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = MahallaButtonVariant.Ghost,
        )
    }
}

/**
 * Системный промпт. Показывается один раз на шаг подтверждения: `LaunchedEffect`
 * привязан к шагу, иначе рекомпозиция (например, от обновления баланса) открыла
 * бы второй диалог поверх первого.
 */
@Composable
private fun BiometricConfirmation(
    amountSum: Long,
    onConfirmed: () -> Unit,
    onRejected: () -> Unit,
) {
    val context = LocalContext.current
    val currency = stringResource(R.string.currency_uzs)
    val title = stringResource(R.string.payment_confirm_title)
    val subtitle = stringResource(
        R.string.payment_confirm_biometric_subtitle,
        MoneyFormatter.withCurrency(amountSum, currency),
    )
    val negative = stringResource(R.string.payment_confirm_biometric_negative)

    LaunchedEffect(amountSum) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            // Промпт показать нечем (превью, нестандартный контекст) —
            // подтверждаем PIN'ом, а не зависаем на пустой шторке.
            onRejected()
        } else {
            showBiometricPrompt(
                activity = activity,
                title = title,
                subtitle = subtitle,
                negativeLabel = negative,
                onSuccess = onConfirmed,
                onCancelled = onRejected,
                onFailed = onRejected,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun PaymentConfirmPinPreview() {
    PreviewSurface {
        PaymentConfirmSheetPreviewContent(
            state = WalletPaymentState(
                amountSum = 84_000,
                availableSum = 1_284_500,
                step = WalletPaymentStep.Confirm,
                method = PaymentConfirmationMethod.Pin,
                pin = OtpFieldState(code = "12"),
            ),
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun PaymentConfirmInsufficientPreview() {
    PreviewSurface {
        PaymentConfirmSheetPreviewContent(
            state = WalletPaymentState(
                amountSum = 84_000,
                availableSum = 12_000,
                step = WalletPaymentStep.Rejected,
                method = PaymentConfirmationMethod.Pin,
                rejection = WalletPaymentRejection.InsufficientFunds(missingSum = 72_000),
            ),
        )
    }
}

/**
 * Превью без `ModalBottomSheet`: в превью он отрисовывается пустым окном, и
 * увидеть вёрстку содержимого нельзя. Поэтому здесь то же содержимое в колонке.
 */
@Composable
private fun PaymentConfirmSheetPreviewContent(state: WalletPaymentState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(
            text = stringResource(R.string.payment_confirm_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        PaymentAmount(state = state)
        if (state.rejection != null) {
            PaymentRejection(state = state, onRetry = {}, onTopUp = {}, onDismiss = {})
        } else {
            PinConfirmation(state = state, onPinChanged = {})
        }
    }
}

/** Индикатор в строку с текстом — как на шаге входа через Telegram. */
private val PROGRESS_SIZE = 20.dp
private val PROGRESS_STROKE = 2.dp
