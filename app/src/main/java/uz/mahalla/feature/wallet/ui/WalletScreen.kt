package uz.mahalla.feature.wallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ScreenSkeleton
import uz.mahalla.ui.theme.TabularNums

/** Демонстрация форматирования сумм с tabular numerals (эпик 1.5). */
private const val DEMO_BALANCE_SUM = 1_284_500L

@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.wallet_title),
        modifier = modifier,
        subtitle = stringResource(R.string.wallet_subtitle),
    ) {
        Text(
            text = MoneyFormatter.withCurrency(
                sum = DEMO_BALANCE_SUM,
                currencyLabel = stringResource(R.string.currency_uzs),
            ),
            style = MaterialTheme.typography.displaySmall.merge(TabularNums),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
