package uz.mahalla.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.messageRes
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Прямоугольник-заглушка с мерцанием. Пульсирует прозрачность, а не бегущий
 * градиент: дешевле по кадрам и спокойнее выглядит на списке из десятка строк.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    height: Dp = MahallaComponentDefaults.skeletonLineHeight,
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier = modifier
            .height(height)
            .alpha(alpha)
            .background(LocalMahallaColors.current.skeleton, shape),
    )
}

/** Скелет одной карточки списка. */
@Composable
fun CardSkeleton(modifier: Modifier = Modifier) {
    MahallaCard(modifier = modifier) {
        SkeletonBox(modifier = Modifier.fillMaxWidth(SKELETON_TITLE_WIDTH), height = 16.dp)
        Box(modifier = Modifier.height(Spacing.item))
        SkeletonBox(modifier = Modifier.fillMaxWidth(SKELETON_SUBTITLE_WIDTH))
        Box(modifier = Modifier.height(Spacing.item))
        SkeletonBox(modifier = Modifier.fillMaxWidth(SKELETON_META_WIDTH))
    }
}

/**
 * Скелет списка. Для TalkBack это один объект «идёт загрузка»: озвучивать
 * десяток пустых прямоугольников бессмысленно.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = DEFAULT_SKELETON_ITEMS,
) {
    val loadingLabel = stringResource(R.string.state_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = loadingLabel
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        repeat(itemCount) { CardSkeleton() }
    }
}

/** Пустое состояние: почему пусто и что можно сделать. */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_empty_title),
    description: String = stringResource(R.string.state_empty_description),
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateMessage(
        modifier = modifier,
        icon = icon,
        iconTint = LocalMahallaColors.current.fgMuted,
        title = title,
        description = description,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

/**
 * Ошибка с повтором. Кнопка обязательна: тупик без действия — самая частая
 * причина, по которой пользователь закрывает приложение.
 */
@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_error_title),
    description: String = stringResource(R.string.error_unknown),
) {
    StateMessage(
        modifier = modifier,
        icon = Icons.Outlined.ErrorOutline,
        iconTint = MaterialTheme.colorScheme.error,
        title = title,
        description = description,
        actionLabel = stringResource(R.string.action_retry),
        onAction = onRetry,
    )
}

/**
 * Ошибка сетевого слоя: текст — сообщение сервера, если он его прислал, иначе
 * единый маппинг [messageRes]. Под кнопкой повтора — раскрываемые подробности
 * ответа (issue #34).
 *
 * Прокрутка обязательна: иконка, заголовок, текст и кнопка повтора занимают
 * половину экрана, а под ними разворачивается тело ответа до 2000 символов —
 * без прокрутки кнопка «Копировать» оказывается за нижней границей ровно в том
 * случае, ради которого блок и делался. При `fontScale 1.5` не помещается и
 * короткий ответ. Все места вызова ([ScreenStateHost]) дают ограниченную
 * высоту; внутрь прокручиваемого родителя это состояние не ставить.
 */
@Composable
fun ApiErrorState(
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorState(
            onRetry = onRetry,
            description = failure.userMessage(),
        )
        failure.server?.let {
            MahallaErrorDetails(
                server = it,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
    }
}

/**
 * Переключатель состояний экрана: загрузка → скелетон, пусто → EmptyState,
 * ошибка → ErrorState, данные → контент. Экраны не собирают эту связку сами,
 * поэтому «пустой список вместо ошибки» невозможен по построению.
 */
@Composable
fun <T> ScreenStateHost(
    state: ScreenState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { ListSkeleton() },
    empty: @Composable () -> Unit = { EmptyState() },
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (state) {
            is ScreenState.Loading -> loading()
            is ScreenState.Empty -> empty()
            is ScreenState.Error -> ApiErrorState(failure = state.failure, onRetry = onRetry)
            is ScreenState.Content -> content(state.data)
        }
    }
}

@Composable
private fun StateMessage(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gutter)
            // Сообщение о смене состояния экрана TalkBack проговаривает сам.
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MahallaComponentDefaults.stateIconSize),
            tint = iconTint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            MahallaButton(
                text = actionLabel,
                onClick = onAction,
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

private const val SHIMMER_DURATION_MS = 700
private const val DEFAULT_SKELETON_ITEMS = 4
private const val SKELETON_TITLE_WIDTH = 0.6f
private const val SKELETON_SUBTITLE_WIDTH = 0.4f
private const val SKELETON_META_WIDTH = 0.8f

@ThemeLanguagePreviews
@Composable
private fun ScreenStatesPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            ListSkeleton(itemCount = 1)
            EmptyState()
            ApiErrorState(failure = ApiFailure(ApiError.NoConnection), onRetry = {})
        }
    }
}
