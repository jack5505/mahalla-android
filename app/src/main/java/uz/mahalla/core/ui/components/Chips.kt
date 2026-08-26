package uz.mahalla.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

@Immutable
data class FilterChipUi(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
)

/**
 * Чип-фильтр. Визуальная высота по макету — 28dp, поэтому чип помещён в
 * контейнер высотой 48dp: попасть пальцем можно и мимо самого чипа (2.4).
 * Выбранность дублируется stateDescription — иначе TalkBack не отличит
 * выбранный чип от обычного.
 */
@Composable
fun MahallaFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val stateLabel = stringResource(
        if (selected) R.string.chip_state_selected else R.string.chip_state_not_selected,
    )
    val mahalla = LocalMahallaColors.current
    Box(
        modifier = modifier.heightIn(min = MahallaComponentDefaults.chipMinTouchHeight),
        contentAlignment = Alignment.Center,
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            label = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
            leadingIcon = when {
                selected -> {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                }

                icon != null -> {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                }

                else -> null
            },
            shape = MaterialTheme.shapes.small,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = mahalla.accentSoft,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            border = BorderStroke(
                MahallaComponentDefaults.borderWidth,
                if (selected) mahalla.accent else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.semantics { stateDescription = stateLabel },
        )
    }
}

/** Горизонтальный ряд фильтров с одиночным выбором. */
@Composable
fun MahallaFilterRow(
    items: List<FilterChipUi>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            MahallaFilterChip(
                label = item.label,
                selected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                icon = item.icon,
            )
        }
    }
}

/**
 * Некликабельный статус-бейдж (открыто/закрыто, статус заказа). Тон задаёт
 * пару цветов из палитры, текст обязателен — цветом смысл не передаём.
 */
@Composable
fun MahallaBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: MahallaTone = MahallaTone.Neutral,
    icon: ImageVector? = null,
) {
    val colors = tone.colors()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.item, vertical = Spacing.item / 2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaChipsPreview() {
    PreviewSurface {
        MahallaFilterRow(
            items = listOf(
                FilterChipUi(id = "food", label = stringResource(R.string.category_food)),
                FilterChipUi(id = "pharmacy", label = stringResource(R.string.category_pharmacy)),
                FilterChipUi(id = "open", label = stringResource(R.string.place_open_now)),
            ),
            selectedId = "food",
            onSelect = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MahallaBadge(text = stringResource(R.string.place_open_now), tone = MahallaTone.Success)
            MahallaBadge(text = stringResource(R.string.place_closed_now), tone = MahallaTone.Neutral)
            MahallaBadge(text = stringResource(R.string.order_status_pending), tone = MahallaTone.Warning)
        }
    }
}
