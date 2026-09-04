package uz.mahalla.feature.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.wallet.domain.TopUpError
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.WalletAmounts
import uz.mahalla.feature.wallet.domain.WalletTopUp
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Пополнение кошелька (issue #93): сумма, платёжная система, переход к оплате.
 *
 * Шторка, а не отдельный экран: форма короткая, а баланс остаётся видимым за
 * ней — на вопрос «сколько добавить» отвечают, глядя на то, сколько есть.
 *
 * Минимум называется в сумах и считается по делителю единиц бэкенда из выдачи
 * баланса ([WalletTopUp.minAmountSum]): подпись под полем обязана называть то
 * число, которое поле примет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopUpSheet(
    state: TopUpState,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    val minLabel = MoneyFormatter.withCurrency(state.minAmountSum, currency)
    val errors = state.visibleErrors
    MahallaBottomSheet(
        onDismiss = { onEvent(WalletEvent.TopUpDismissed) },
        modifier = modifier,
        title = stringResource(R.string.wallet_top_up_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaTextField(
                value = state.draft.amountText,
                onValueChange = { onEvent(WalletEvent.TopUpAmountChanged(it)) },
                label = stringResource(R.string.wallet_top_up_amount),
                // Клавиатура только цифровая: сумма целая, тийинами кошелёк
                // не пополняют.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                supportingText = stringResource(R.string.wallet_top_up_amount_hint, minLabel),
                errorText = errors.amountMessage(minLabel = minLabel, currency = currency),
                enabled = !state.isSubmitting,
            )

            Text(
                text = stringResource(R.string.wallet_top_up_provider),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Провайдер не выбран заранее: у трёх систем разные комиссии и
            // разные приложения, и выбор за человека вернул бы его с полпути.
            TopUpProvider.entries.forEach { provider ->
                MahallaChoiceCard(
                    title = stringResource(provider.labelRes()),
                    selected = state.draft.provider == provider,
                    onClick = { onEvent(WalletEvent.TopUpProviderSelected(provider)) },
                    enabled = !state.isSubmitting,
                )
            }
            if (TopUpError.ProviderRequired in errors) {
                Text(
                    text = stringResource(R.string.wallet_top_up_error_provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.wallet_top_up_provider_note),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMahallaColors.current.fgMuted,
            )

            // Отказ остаётся в шторке рядом с набранной суммой: закрыть её
            // значило бы потерять и объяснение, и работу человека (issue #34).
            state.failure?.let { failure ->
                Text(
                    text = failure.userMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                failure.server?.let { MahallaErrorDetails(server = it) }
            }

            MahallaButton(
                text = stringResource(R.string.wallet_top_up_submit),
                onClick = { onEvent(WalletEvent.TopUpSubmitted) },
                modifier = Modifier.fillMaxWidth(),
                state = if (state.isSubmitting) ButtonState.Loading else ButtonState.Default,
            )
        }
    }
}

/**
 * Одна причина на поле: три сообщения про одну сумму человек прочитает как
 * три разных требования.
 */
@Composable
private fun Set<TopUpError>.amountMessage(minLabel: String, currency: String): String? = when {
    TopUpError.AmountRequired in this ->
        stringResource(R.string.wallet_top_up_error_amount_required)

    TopUpError.AmountTooSmall in this ->
        stringResource(R.string.wallet_top_up_error_amount_small, minLabel)

    TopUpError.AmountTooLarge in this -> stringResource(
        R.string.wallet_top_up_error_amount_large,
        MoneyFormatter.withCurrency(WalletTopUp.MAX_AMOUNT_SUM, currency),
    )

    else -> null
}

/** Имена платёжных систем — бренды, они не переводятся, но и не зашиты в код. */
internal fun TopUpProvider.labelRes(): Int = when (this) {
    TopUpProvider.Payme -> R.string.wallet_top_up_provider_payme
    TopUpProvider.Click -> R.string.wallet_top_up_provider_click
    TopUpProvider.Uzum -> R.string.wallet_top_up_provider_uzum
}

/**
 * Что стало с платежом — на экране, а не в шторке: к этому моменту шторка
 * закрыта, и сообщение в ней никто бы не увидел.
 *
 * Платёж ушёл, человек вернулся: деньги зачисляет колбэк провайдера, то есть
 * не мгновенно, и без этой строки неизменившийся баланс читается как
 * потерянный платёж. Открыть форму было нечем — об этом тоже надо сказать:
 * тап без последствий читается как сломанная кнопка.
 */
@Composable
fun PaymentNotice(
    started: PaymentStarted?,
    openFailed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        if (openFailed) {
            Text(
                text = stringResource(R.string.wallet_top_up_error_no_browser),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        started?.let {
            Text(
                text = stringResource(
                    R.string.wallet_top_up_started,
                    MoneyFormatter.withCurrency(it.amountSum, currency),
                ),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MahallaButton(
            text = stringResource(R.string.wallet_top_up_started_dismiss),
            onClick = onDismiss,
            variant = MahallaButtonVariant.Ghost,
            fillWidth = false,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun TopUpSheetPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            PaymentNotice(
                started = PaymentStarted(
                    amountSum = 250_000,
                    provider = TopUpProvider.Payme,
                ),
                openFailed = false,
                onDismiss = {},
            )
            Text(
                text = stringResource(
                    R.string.wallet_top_up_amount_hint,
                    MoneyFormatter.withCurrency(
                        WalletTopUp.minAmountSum(WalletAmounts.TIYIN_IN_SOM),
                        stringResource(R.string.currency_uzs),
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
