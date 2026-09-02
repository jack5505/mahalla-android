package uz.mahalla.feature.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.notifications.domain.AppNotification
import uz.mahalla.feature.notifications.domain.NotificationType
import uz.mahalla.feature.notifications.domain.isActionable
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Центр уведомлений (issue #81, задача T11).
 *
 * Тексты уведомлений пишет бэкенд — экран их только показывает. Своих строк
 * под типы здесь нет: список типов открытый, и незнакомый тип остался бы без
 * текста вовсе.
 */
@Composable
fun NotificationsScreen(
    onOrderClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NotificationsEffect.OpenOrder -> onOrderClick(effect.orderId)
            }
        }
    }

    // Уведомление могло прийти, пока приложение было в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(NotificationsEvent.ScreenResumed)
    }

    NotificationsContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun NotificationsContentScreen(
    state: NotificationsState,
    onEvent: (NotificationsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.notifications_title),
            onBack = onBack,
            actions = {
                // Кнопки нет, когда читать нечего: неактивная иконка в топбаре
                // выглядит как сломанная.
                if (state.unreadCount > 0) {
                    MahallaIconButton(
                        icon = Icons.Outlined.DoneAll,
                        contentDescription = stringResource(R.string.notifications_mark_all_read),
                        onClick = { onEvent(NotificationsEvent.MarkAllRead) },
                        enabled = !state.isMarkingRead,
                    )
                }
            },
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(NotificationsEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ «прочитать всё» — над списком, а не вместо него:
                // уведомления уже на экране, и прятать их незачем.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") {
                        InlineFailure(
                            failure = failure,
                            onRetry = { onEvent(NotificationsEvent.MarkAllRead) },
                        )
                    }
                }
                notificationItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.notificationItems(
    state: NotificationsState,
    onEvent: (NotificationsEvent) -> Unit,
) {
    when (val items = state.items) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.notifications_empty_title),
                description = stringResource(R.string.notifications_empty_description),
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = items.failure,
                onRetry = { onEvent(NotificationsEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(items.data, key = AppNotification::id) { notification ->
                NotificationCard(
                    notification = notification,
                    onClick = { onEvent(NotificationsEvent.NotificationClicked(notification.id)) },
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = items.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/**
 * Карточка уведомления. Непрочитанное отличается точкой у заголовка и жирным
 * начертанием: одним цветом фона разницу не видно ни при высокой яркости, ни
 * в монохромном режиме доступности.
 *
 * Кликабельно только то, у чего есть куда вести ([isActionable]): нажатие без
 * последствий читается как сломанный экран.
 */
@Composable
private fun NotificationCard(
    notification: AppNotification,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(
        modifier = modifier,
        onClick = if (notification.isActionable) onClick else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!notification.isRead) {
                UnreadDot()
            }
            Text(
                text = notification.title
                    ?: stringResource(R.string.notifications_default_title),
                modifier = Modifier.weight(1f),
                style = if (notification.isRead) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        notification.body?.let { body ->
            Text(
                text = body,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = if (notification.isRead) colors.fgMuted else MaterialTheme.colorScheme.onSurface,
            )
        }
        notification.createdAt?.let { createdAt ->
            Text(
                text = DateTimeFormatters.dateTime(createdAt),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = colors.fgMuted,
            )
        }
    }
}

/** Точка непрочитанного. Для TalkBack её нет — состояние несёт заголовок. */
@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(UNREAD_DOT)
            .clip(CircleShape)
            .background(LocalMahallaColors.current.accent),
    )
}

/**
 * Отказ внутри списка: текст сервера, подробности и повтор. `ApiErrorState`
 * здесь не годится — он прокручивается сам (см. [notificationItems]).
 */
@Composable
private fun InlineFailure(
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        MahallaButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            variant = MahallaButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: NotificationsState,
    itemCount: Int,
    onEvent: (NotificationsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(NotificationsEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(NotificationsEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

private const val LIST_SKELETONS = 4
private val LOAD_MORE_INDICATOR = 24.dp
private val UNREAD_DOT = 8.dp

@ThemeLanguagePreviews
@Composable
private fun NotificationsScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        NotificationsContentScreen(
            state = NotificationsState(
                items = ScreenState.Content(
                    listOf(
                        AppNotification(
                            id = "n-1",
                            title = "Buyurtma qabul qilindi",
                            body = "Osh Markazi buyurtmangizni tayyorlamoqda.",
                            type = NotificationType.OrderStatusUpdated,
                            entityId = "o-1",
                            isRead = false,
                            createdAt = Instant.parse("2026-08-31T09:12:00Z"),
                        ),
                        AppNotification(
                            id = "n-2",
                            title = "Yangi aksiya",
                            body = "Dorixona №7 da 20% chegirma.",
                            type = NotificationType.PromotionCreated,
                            entityId = "p-1",
                            isRead = true,
                            createdAt = Instant.parse("2026-08-30T18:40:00Z"),
                        ),
                    ),
                ),
                unreadCount = 1,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
