package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Строка-переключатель. Кликабельна целиком (`toggleable` на всей строке, а не
 * на самом Switch) — так цель нажатия заведомо больше 48dp, а TalkBack читает
 * заголовок, описание и состояние одним объектом с ролью Switch.
 */
@Composable
fun MahallaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val stateLabel = stringResource(if (checked) R.string.switch_state_on else R.string.switch_state_off)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.switchRowMinHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { stateDescription = stateLabel }
            .padding(vertical = Spacing.item, horizontal = Spacing.card),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
        }
        // null: клик обрабатывает вся строка, иначе TalkBack найдёт два объекта.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Строка с флажком — согласие с офертой и подобные обязательные отметки
 * (3.2). Отдельно от [MahallaSwitchRow]: переключатель означает «настройка
 * включена», флажок — «пользователь подтвердил», и роль в TalkBack тоже
 * разная (Checkbox против Switch).
 *
 * Ссылка на документ вынесена отдельной кнопкой: клик по тексту с флажком
 * одновременно и открывал бы оферту, и переключал согласие.
 */
@Composable
fun MahallaCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    linkLabel: String? = null,
    onLinkClick: (() -> Unit)? = null,
) {
    val stateLabel = stringResource(
        if (checked) R.string.checkbox_state_checked else R.string.checkbox_state_unchecked,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MahallaComponentDefaults.checkboxRowMinHeight)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .semantics { stateDescription = stateLabel }
                .padding(vertical = Spacing.item, horizontal = Spacing.card),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // null: клик обрабатывает вся строка, иначе TalkBack найдёт два объекта.
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (linkLabel != null && onLinkClick != null) {
            MahallaButton(
                text = linkLabel,
                onClick = onLinkClick,
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        }
    }
}

/**
 * Сегментированный переключатель (язык, тема, «доставка/самовывоз»). Собран
 * из Surface вместо экспериментального SegmentedButton: API кита не должен
 * зависеть от того, что Material 3 ещё перебирает сигнатуры.
 */
@Composable
fun MahallaSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val mahalla = LocalMahallaColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .padding(Spacing.item / 2)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MahallaComponentDefaults.segmentMinHeight)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (selected) mahalla.accentSoft else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.item),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaTogglesPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaSwitchRow(
                title = stringResource(R.string.onboarding_biometric_title),
                description = stringResource(R.string.onboarding_geo_subtitle),
                checked = true,
                onCheckedChange = {},
            )
            MahallaSwitchRow(
                title = stringResource(R.string.profile_theme),
                checked = false,
                onCheckedChange = {},
            )
            MahallaCheckboxRow(
                title = stringResource(R.string.onboarding_phone_consent),
                checked = false,
                onCheckedChange = {},
                linkLabel = stringResource(R.string.onboarding_phone_consent_link),
                onLinkClick = {},
            )
            MahallaSegmentedControl(
                options = listOf(
                    stringResource(R.string.theme_system),
                    stringResource(R.string.theme_light),
                    stringResource(R.string.theme_dark),
                ),
                selectedIndex = 0,
                onSelect = {},
            )
        }
    }
}
