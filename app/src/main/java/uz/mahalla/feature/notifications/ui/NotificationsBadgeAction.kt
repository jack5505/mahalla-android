package uz.mahalla.feature.notifications.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews

/**
 * Иконка уведомлений с бейджем непрочитанного для топбара (issue #81).
 *
 * Своя ViewModel, а не поле в состоянии главной: счётчик к каталогу отношения
 * не имеет, а обновляться обязан на каждом возврате на экран — в том числе
 * после того, как на соседнем экране нажали «прочитать всё».
 */
@Composable
fun NotificationsBadgeAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsBadgeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(NotificationsBadgeEvent.ScreenResumed)
    }

    NotificationsBadgeButton(
        unreadCount = state.unreadCount,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Отделено от Hilt ради превью и читаемости. */
@Composable
fun NotificationsBadgeButton(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.notifications_title)
    // Одна подпись на иконку и бейдж: TalkBack иначе читает «уведомления» и
    // отдельным объектом голое число.
    val description = if (unreadCount > 0) {
        "$title, " + pluralStringResource(
            R.plurals.notifications_unread_count,
            unreadCount,
            unreadCount,
        )
    } else {
        title
    }

    BadgedBox(
        modifier = modifier,
        badge = {
            if (unreadCount > 0) {
                // Бейдж уже озвучен подписью кнопки — для TalkBack он пустой.
                Badge(modifier = Modifier.clearAndSetSemantics {}) {
                    Text(text = badgeLabel(unreadCount))
                }
            }
        },
    ) {
        MahallaIconButton(
            icon = Icons.Outlined.Notifications,
            contentDescription = description,
            onClick = onClick,
        )
    }
}

/**
 * Трёхзначное число в бейдж не помещается и на узком экране наезжает на
 * соседнюю иконку: точное количество непрочитанного всё равно ничего не
 * решает, а «много» читается с одного взгляда.
 */
internal fun badgeLabel(unreadCount: Int): String =
    if (unreadCount > BADGE_LIMIT) "$BADGE_LIMIT+" else unreadCount.toString()

private const val BADGE_LIMIT = 99

@ThemeLanguagePreviews
@Composable
private fun NotificationsBadgePreview() {
    PreviewSurface {
        NotificationsBadgeButton(unreadCount = 3, onClick = {})
        NotificationsBadgeButton(unreadCount = 120, onClick = {})
        NotificationsBadgeButton(unreadCount = 0, onClick = {})
    }
}
