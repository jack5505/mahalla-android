package uz.mahalla.feature.food.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaQuantityStepper
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.feature.food.domain.MenuOption
import uz.mahalla.feature.food.domain.OptionGroup
import uz.mahalla.feature.food.domain.SelectionError
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Шторка модификаторов (эпик 5.1).
 *
 * Одиночная группа — радио, множественная — чекбоксы: роль задаётся
 * семантикой, а не только видом кружка, иначе TalkBack прочитает обе одинаково.
 * Правила выбора и цена приходят из домена — экран их не пересчитывает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    sheet: OptionsSheetState,
    onEvent: (MenuEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    MahallaBottomSheet(onDismiss = onDismiss, modifier = modifier, title = sheet.item.name) {
        if (sheet.item.description != null) {
            Text(
                text = sheet.item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        sheet.item.optionGroups.forEach { group ->
            OptionGroupBlock(
                group = group,
                selected = sheet.selectedOptionIds,
                errorText = sheet.visibleErrors.groupError(group),
                onToggle = { optionId -> onEvent(MenuEvent.OptionToggled(group.id, optionId)) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MahallaQuantityStepper(
                quantity = sheet.quantity,
                onQuantityChange = { onEvent(MenuEvent.SheetQuantityChanged(it)) },
                // В шторке «−» на единице ничего не удаляет: позиции в корзине
                // ещё нет, удалять нечего.
                removable = false,
                itemName = sheet.item.name,
            )
            Text(
                text = MoneyFormatter.withCurrency(sheet.totalSum, currency),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
            )
        }

        MahallaButton(
            text = stringResource(R.string.menu_add_to_cart),
            onClick = { onEvent(MenuEvent.AddToCartClicked) },
        )
    }
}

@Composable
private fun OptionGroupBlock(
    group: OptionGroup,
    selected: Set<String>,
    errorText: String?,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = if (group.isRequired) {
                stringResource(R.string.menu_group_required, group.name)
            } else {
                group.name
            },
        )
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(
            modifier = if (group.isSingleChoice) Modifier.selectableGroup() else Modifier,
        ) {
            group.options.forEach { option ->
                OptionRow(
                    option = option,
                    checked = option.id in selected,
                    singleChoice = group.isSingleChoice,
                    onToggle = { onToggle(option.id) },
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    option: MenuOption,
    checked: Boolean,
    singleChoice: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mahalla = LocalMahallaColors.current
    val currency = stringResource(R.string.currency_uzs)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.minTouchTarget)
            .then(
                if (singleChoice) {
                    Modifier.selectable(
                        selected = checked,
                        enabled = option.isAvailable,
                        role = Role.RadioButton,
                        onClick = onToggle,
                    )
                } else {
                    Modifier.toggleable(
                        value = checked,
                        enabled = option.isAvailable,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    )
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (singleChoice) {
            RadioButton(selected = checked, onClick = null, enabled = option.isAvailable)
        } else {
            Checkbox(checked = checked, onCheckedChange = null, enabled = option.isAvailable)
        }
        Text(
            text = option.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (option.isAvailable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                mahalla.fgMuted
            },
        )
        Text(
            text = when {
                !option.isAvailable -> stringResource(R.string.menu_item_unavailable)
                option.priceDeltaSum == 0L -> ""
                else -> MoneyFormatter.withCurrency(option.priceDeltaSum, currency)
                    .let { if (option.priceDeltaSum > 0) "+$it" else it }
            },
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = mahalla.fgMuted,
        )
    }
}

/** Текст ошибки для конкретной группы; `null` — с группой всё в порядке. */
@Composable
private fun List<SelectionError>.groupError(group: OptionGroup): String? {
    val error = firstOrNull {
        (it as? SelectionError.RequiredGroup)?.groupId == group.id ||
            (it as? SelectionError.OptionUnavailable)?.groupId == group.id
    } ?: return null

    return when (error) {
        is SelectionError.RequiredGroup -> if (group.isSingleChoice) {
            stringResource(R.string.menu_group_error_required_one)
        } else {
            stringResource(R.string.menu_group_error_required_many, group.minChoices)
        }

        is SelectionError.OptionUnavailable -> stringResource(R.string.menu_group_error_unavailable)
        SelectionError.Unavailable -> null
    }
}
