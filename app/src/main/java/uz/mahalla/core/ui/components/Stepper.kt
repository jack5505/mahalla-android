package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Степпер количества (эпик 5.2): «−  2  +».
 *
 * Число — моноширинными цифрами: при 9 → 10 ширина не должна прыгать и таскать
 * за собой обе кнопки. На единице «−» превращается в «удалить»: иначе
 * единственный способ выкинуть позицию из корзины — свайп, о котором никто не
 * догадается.
 */
@Composable
fun MahallaQuantityStepper(
    quantity: Int,
    onQuantityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minQuantity: Int = 1,
    maxQuantity: Int = MahallaComponentDefaults.maxStepperQuantity,
    removable: Boolean = true,
    itemName: String? = null,
) {
    val canRemove = removable && quantity <= minQuantity
    val decreaseLabel = when {
        canRemove -> stringResource(R.string.action_delete)
        else -> stringResource(R.string.quantity_decrease)
    }
    val quantityLabel = itemName
        ?.let { stringResource(R.string.quantity_of_item, quantity, it) }
        ?: stringResource(R.string.quantity_value, quantity)

    Row(
        modifier = modifier.heightIn(min = MahallaComponentDefaults.stepperMinHeight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MahallaIconButton(
            icon = if (canRemove) Icons.Outlined.Delete else Icons.Outlined.Remove,
            contentDescription = decreaseLabel,
            onClick = { onQuantityChange(quantity - 1) },
            enabled = removable || quantity > minQuantity,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = quantity.toString(),
                modifier = Modifier
                    .widthIn(min = MahallaComponentDefaults.stepperValueMinWidth)
                    .semantics { contentDescription = quantityLabel },
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                textAlign = TextAlign.Center,
            )
        }
        MahallaIconButton(
            icon = Icons.Outlined.Add,
            contentDescription = stringResource(R.string.quantity_increase),
            onClick = { onQuantityChange(quantity + 1) },
            enabled = quantity < maxQuantity,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun QuantityStepperPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaQuantityStepper(quantity = 1, onQuantityChange = {})
            MahallaQuantityStepper(quantity = 12, onQuantityChange = {})
        }
    }
}
