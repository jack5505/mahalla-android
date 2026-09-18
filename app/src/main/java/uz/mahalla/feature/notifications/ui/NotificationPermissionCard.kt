package uz.mahalla.feature.notifications.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Карточка «уведомления выключены» (эпик 11).
 *
 * Показывается там, где человек сам пришёл за уведомлениями, — в их центре и в
 * их настройках. Это и есть «в нужный момент, а не на старте»: диалог на
 * первом экране закрывают не глядя, а второго раза система не даёт.
 *
 * @param canRequest право на системный диалог, судя по всему, ещё не
 * потрачено — тогда сверху стоит «Разрешить». Путь в системные настройки при
 * этом остаётся на карточке всегда: потрачено оно или нет, приложение знает
 * только в пределах жизни экрана, а кнопка, которая молча ничего не делает,
 * читается как сломанное приложение.
 */
@Composable
fun NotificationPermissionCard(
    canRequest: Boolean,
    onRequest: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = stringResource(R.string.notification_permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.notification_permission_description),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            if (canRequest) {
                MahallaButton(
                    text = stringResource(R.string.notification_permission_allow),
                    onClick = onRequest,
                    variant = MahallaButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            // Путь в системные настройки есть **всегда**, а не только когда
            // диалог уже потрачен: потрачен он или нет, приложение знает лишь
            // в пределах жизни экрана — система такого вопроса не отвечает.
            // Ошибись мы в этой догадке, единственная кнопка молча ничего не
            // делала бы, а так рабочий путь на экране остаётся в любом случае.
            MahallaButton(
                text = stringResource(R.string.notification_permission_open_settings),
                onClick = onOpenSystemSettings,
                variant = if (canRequest) {
                    MahallaButtonVariant.Ghost
                } else {
                    MahallaButtonVariant.Secondary
                },
                fillWidth = false,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun NotificationPermissionCardPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            NotificationPermissionCard(
                canRequest = true,
                onRequest = {},
                onOpenSystemSettings = {},
            )
            NotificationPermissionCard(
                canRequest = false,
                onRequest = {},
                onOpenSystemSettings = {},
            )
        }
    }
}
