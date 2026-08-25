package uz.mahalla.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.FocusButtonShape
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/** Варианты кнопок по DESIGN-SYSTEM: основная, вторичная, «призрак», опасная. */
enum class MahallaButtonVariant { Primary, Secondary, Ghost, Destructive }

/**
 * Состояние кнопки. `loading` не сводится к `enabled = false`: выключенная
 * кнопка недоступна, а грузящаяся — временно занята, и TalkBack должен
 * сообщать об этом по-разному.
 */
@Immutable
data class ButtonState(
    val enabled: Boolean = true,
    val loading: Boolean = false,
) {
    val isClickable: Boolean get() = enabled && !loading

    companion object {
        val Default = ButtonState()
        val Disabled = ButtonState(enabled = false)
        val Loading = ButtonState(loading = true)
    }
}

@Composable
fun MahallaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MahallaButtonVariant = MahallaButtonVariant.Primary,
    state: ButtonState = ButtonState.Default,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    val loadingLabel = stringResource(R.string.state_loading)
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            // Визуальная высота по макету — 44dp, но цель нажатия не меньше 48dp (2.4).
            .defaultMinSize(minHeight = MahallaComponentDefaults.buttonMinHeight)
            .semantics {
                if (state.loading) stateDescription = loadingLabel
            },
        // Пока идёт загрузка кнопка не кликается, но остаётся видимой и
        // озвученной: пользователь понимает, что запрос уже ушёл.
        enabled = state.isClickable,
        shape = FocusButtonShape,
        colors = variant.buttonColors(),
        border = variant.border(),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.item, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MahallaComponentDefaults.progressIndicatorSize),
                    color = LocalContentColor.current,
                    strokeWidth = MahallaComponentDefaults.progressStrokeWidth,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    // Иконка дублирует надпись — для TalkBack она пустая.
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                )
            }
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Кнопка-иконка: сама иконка может быть 20dp, но зона нажатия — 48dp.
 * [contentDescription] обязателен — без него TalkBack прочитает «кнопка».
 */
@Composable
fun MahallaIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(MahallaComponentDefaults.iconButtonSize),
        enabled = enabled,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun MahallaButtonVariant.buttonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    val mahalla = LocalMahallaColors.current
    return when (this) {
        MahallaButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
        )

        MahallaButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = mahalla.accentSoft,
            contentColor = scheme.onSecondaryContainer,
        )

        MahallaButtonVariant.Ghost -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurface,
        )

        MahallaButtonVariant.Destructive -> ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        )
    }
}

@Composable
private fun MahallaButtonVariant.border(): BorderStroke? =
    if (this == MahallaButtonVariant.Ghost) {
        BorderStroke(MahallaComponentDefaults.borderWidth, MaterialTheme.colorScheme.outline)
    } else {
        null
    }

/**
 * Подпись под кнопкой (например «Кодни қайта юбориш через 30 сек»): читается
 * TalkBack как часть блока кнопки, поэтому семантика склеивается.
 */
@Composable
fun ButtonCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
        style = MaterialTheme.typography.labelSmall,
        color = LocalMahallaColors.current.fgMuted,
    )
}

@ThemeLanguagePreviews
@Composable
private fun MahallaButtonPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaButton(
                text = stringResource(R.string.onboarding_phone_action),
                onClick = {},
                icon = Icons.Outlined.Send,
            )
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = {},
                variant = MahallaButtonVariant.Secondary,
            )
            MahallaButton(
                text = stringResource(R.string.action_close),
                onClick = {},
                variant = MahallaButtonVariant.Ghost,
            )
            MahallaButton(
                text = stringResource(R.string.action_delete),
                onClick = {},
                variant = MahallaButtonVariant.Destructive,
                icon = Icons.Outlined.Delete,
            )
            MahallaButton(
                text = stringResource(R.string.onboarding_phone_action),
                onClick = {},
                state = ButtonState.Disabled,
            )
            MahallaButton(
                text = stringResource(R.string.onboarding_phone_action),
                onClick = {},
                state = ButtonState.Loading,
            )
        }
    }
}
