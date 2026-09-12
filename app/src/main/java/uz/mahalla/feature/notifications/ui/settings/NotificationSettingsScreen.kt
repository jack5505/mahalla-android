package uz.mahalla.feature.notifications.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings
import uz.mahalla.feature.notifications.domain.QuietHours
import uz.mahalla.feature.notifications.domain.formatMinuteOfDay
import uz.mahalla.feature.notifications.ui.NotificationPermissionCard
import uz.mahalla.feature.notifications.ui.rememberNotificationPermissionState
import uz.mahalla.feature.notifications.push.descriptionRes
import uz.mahalla.feature.notifications.push.titleRes
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Настройки уведомлений (эпик 11): категории, тихие часы и разрешение.
 *
 * Названия категорий берутся из тех же строк, что уходят в системные каналы
 * (`NotificationCategory.titleRes`): если экран приложения и системные
 * настройки называют одно и то же по-разному, человек считает, что это разные
 * настройки, и выключает обе.
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Разрешение живёт в Compose, а не во ViewModel: системный диалог требует
    // реестра результатов Activity, которого у ViewModel нет.
    val permission = rememberNotificationPermissionState()

    NotificationSettingsContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
        permissionEnabled = permission.enabled,
        canRequestPermission = permission.canRequest,
        onRequestPermission = permission.request,
        onOpenSystemSettings = permission.openSystemSettings,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun NotificationSettingsContentScreen(
    state: NotificationSettingsState,
    onEvent: (NotificationSettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    permissionEnabled: Boolean = true,
    canRequestPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    onOpenSystemSettings: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.notification_settings_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            if (!permissionEnabled) {
                NotificationPermissionCard(
                    canRequest = canRequestPermission,
                    onRequest = onRequestPermission,
                    onOpenSystemSettings = onOpenSystemSettings,
                )
            }
            // Сборка без ключей Firebase (см. app/build.gradle.kts): предлагать
            // здесь разрешение бессмысленно — пуш всё равно не придёт, и
            // честнее сказать это прямо, чем оставить настройки, которые ни на
            // что не влияют.
            if (!state.pushConfigured) {
                PushUnavailableNote()
            }

            SectionHeader(title = stringResource(R.string.notification_settings_categories))
            MahallaCard {
                NotificationCategory.entries.forEach { category ->
                    MahallaSwitchRow(
                        title = stringResource(category.titleRes()),
                        checked = state.settings.isEnabled(category),
                        onCheckedChange = { enabled ->
                            onEvent(
                                NotificationSettingsEvent.CategoryToggled(category, enabled),
                            )
                        },
                        description = stringResource(category.descriptionRes()),
                    )
                }
            }

            SectionHeader(title = stringResource(R.string.notification_settings_quiet_hours))
            QuietHoursCard(quietHours = state.settings.quietHours, onEvent = onEvent)
        }
    }

    state.editing?.let { bound ->
        HourPickerDialog(
            bound = bound,
            selectedHour = when (bound) {
                QuietHoursBound.From -> state.settings.quietHours.from
                QuietHoursBound.To -> state.settings.quietHours.to
            } / QuietHours.MINUTES_IN_HOUR,
            onPick = { hour ->
                onEvent(NotificationSettingsEvent.QuietHoursPicked(bound, hour))
            },
            onDismiss = { onEvent(NotificationSettingsEvent.QuietHoursEditDismissed) },
        )
    }
}

/**
 * Тихие часы: включатель и две границы.
 *
 * Границы показываются и выключенными — иначе, чтобы просто посмотреть, с
 * какого часа настроена тишина, пришлось бы её включить.
 */
@Composable
private fun QuietHoursCard(
    quietHours: QuietHours,
    onEvent: (NotificationSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        MahallaSwitchRow(
            title = stringResource(R.string.notification_settings_quiet_hours_enabled),
            checked = quietHours.enabled,
            onCheckedChange = { onEvent(NotificationSettingsEvent.QuietHoursToggled(it)) },
            description = stringResource(R.string.notification_settings_quiet_hours_description),
        )
        MahallaListItem(
            title = stringResource(R.string.notification_settings_quiet_hours_from),
            leadingIcon = Icons.Outlined.Bedtime,
            trailingText = quietHours.from.formatMinuteOfDay(),
            onClick = {
                onEvent(
                    NotificationSettingsEvent.QuietHoursEditRequested(QuietHoursBound.From),
                )
            },
        )
        MahallaListItem(
            title = stringResource(R.string.notification_settings_quiet_hours_to),
            trailingText = quietHours.to.formatMinuteOfDay(),
            onClick = {
                onEvent(NotificationSettingsEvent.QuietHoursEditRequested(QuietHoursBound.To))
            },
        )
    }
}

/**
 * Выбор часа. Именно часа, а не времени с минутами: тихие часы — это «ночью не
 * будить», а не будильник, и колесо минут здесь только добавляет промахов.
 */
@Composable
private fun HourPickerDialog(
    bound: QuietHoursBound,
    selectedHour: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            MahallaButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        },
        title = {
            Text(
                text = stringResource(
                    when (bound) {
                        QuietHoursBound.From -> R.string.notification_settings_quiet_hours_from
                        QuietHoursBound.To -> R.string.notification_settings_quiet_hours_to
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                // Двадцать четыре строки в диалог не влезают: без прокрутки
                // нижние часы недостижимы на любом экране.
                modifier = Modifier
                    .heightIn(max = HOUR_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                (0 until HOURS_IN_DAY).forEach { hour ->
                    MahallaListItem(
                        title = (hour * QuietHours.MINUTES_IN_HOUR).formatMinuteOfDay(),
                        showChevron = false,
                        trailingText = if (hour == selectedHour) {
                            stringResource(R.string.notification_settings_quiet_hours_selected)
                        } else {
                            null
                        },
                        onClick = { onPick(hour) },
                    )
                }
            }
        },
    )
}

/** Сборка без ключей Firebase: объяснение вместо неработающих настроек. */
@Composable
private fun PushUnavailableNote(modifier: Modifier = Modifier) {
    MahallaCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.notification_settings_push_unavailable),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

private const val HOURS_IN_DAY = 24
private val HOUR_LIST_MAX_HEIGHT = 320.dp

@ThemeLanguagePreviews
@Composable
private fun NotificationSettingsScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        NotificationSettingsContentScreen(
            state = NotificationSettingsState(
                settings = NotificationSettings(
                    mutedCategories = setOf(NotificationCategory.Marketing),
                    quietHours = QuietHours(enabled = true),
                ),
            ),
            onEvent = {},
            onBack = {},
            permissionEnabled = false,
            canRequestPermission = true,
        )
    }
}
