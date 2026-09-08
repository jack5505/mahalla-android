package uz.mahalla.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Storefront
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Карточка-выбор: заголовок, объяснение и отметка выбранности.
 *
 * Отдельно от [MahallaFilterChip] и [MahallaSegmentedControl]: там выбор
 * умещается в слово, а здесь у варианта есть цена («вы регистрируете
 * заведение, его проверит модерация») — без объяснения выбор роли в issue #84
 * пришлось бы угадывать по названию.
 *
 * Вся карточка — одна цель нажатия с ролью `RadioButton`: `RadioButton`
 * внутри без своего `onClick`, иначе TalkBack нашёл бы два объекта на одну
 * строку. Выбранность дублируется рамкой **и** `stateDescription` — цвет не
 * единственный носитель смысла (2.4).
 */
@Composable
fun MahallaChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    note: String? = null,
    enabled: Boolean = true,
) {
    val stateLabel = stringResource(
        if (selected) R.string.chip_state_selected else R.string.chip_state_not_selected,
    )
    val mahalla = LocalMahallaColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.choiceCardMinHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { stateDescription = stateLabel },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) mahalla.accentSoft else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            MahallaComponentDefaults.borderWidth,
            if (selected) mahalla.accent else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.card),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.stateIconSize),
                    tint = mahalla.accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.item / 2),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
                // Отметка «анкета заполнена» и подобное: текстом, а не галочкой
                // на пустом месте — иначе непонятно, к чему она относится.
                if (note != null) {
                    MahallaBadge(text = note, tone = MahallaTone.Success)
                }
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaChoiceCardPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaChoiceCard(
                title = stringResource(R.string.role_customer_title),
                description = stringResource(R.string.role_customer_description),
                icon = Icons.Outlined.ShoppingBag,
                note = stringResource(R.string.role_form_filled),
                selected = true,
                onClick = {},
            )
            MahallaChoiceCard(
                title = stringResource(R.string.role_provider_title),
                description = stringResource(R.string.role_provider_description),
                icon = Icons.Outlined.Storefront,
                selected = false,
                onClick = {},
            )
        }
    }
}
