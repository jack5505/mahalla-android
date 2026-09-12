package uz.mahalla.feature.notifications.ui.settings

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings

/**
 * Состояние экрана настроек уведомлений (эпик 11).
 *
 * @param settings категории и тихие часы. Всё локальное: серверных настроек
 * уведомлений у бэкенда нет (сверка `/v3/api-docs` 2026-09-09).
 * @param pushConfigured в сборку положили ключи Firebase. `false` — пуши не
 * придут ни при каких настройках, и экран говорит об этом прямо, вместо того
 * чтобы предлагать разрешение, от которого ничего не изменится.
 * @param editing какую границу тихих часов сейчас выбирают; `null` — диалога
 * нет.
 */
data class NotificationSettingsState(
    val settings: NotificationSettings = NotificationSettings(),
    val pushConfigured: Boolean = true,
    val editing: QuietHoursBound? = null,
) : UiState

/** Какую из двух границ тихих часов правят. */
enum class QuietHoursBound { From, To }

sealed interface NotificationSettingsEvent : UiEvent {

    data class CategoryToggled(
        val category: NotificationCategory,
        val enabled: Boolean,
    ) : NotificationSettingsEvent

    data class QuietHoursToggled(val enabled: Boolean) : NotificationSettingsEvent

    /** Открыть выбор границы. */
    data class QuietHoursEditRequested(val bound: QuietHoursBound) : NotificationSettingsEvent

    /** Выбрали час; минуты в интерфейсе не выбираются — см. экран. */
    data class QuietHoursPicked(val bound: QuietHoursBound, val hour: Int) :
        NotificationSettingsEvent

    data object QuietHoursEditDismissed : NotificationSettingsEvent
}

/**
 * Эффектов у экрана нет: всё, что он делает, — пишет в DataStore, а системный
 * диалог разрешения живёт в самом Compose (`rememberNotificationPermissionState`),
 * потому что ему нужен реестр результатов Activity, а не ViewModel.
 */
sealed interface NotificationSettingsEffect : UiEffect
