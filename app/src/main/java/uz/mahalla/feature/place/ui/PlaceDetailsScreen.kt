package uz.mahalla.feature.place.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonCaption
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaAsyncImage
import uz.mahalla.core.ui.components.MahallaAvatar
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaRatingInput
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.components.SkeletonBox
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.ui.distanceLabel
import uz.mahalla.feature.media.domain.MediaFile
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import uz.mahalla.feature.place.domain.ReviewDraft
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.promotions.ui.PromotionCard
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Карточка места (эпик 4.4): фото, описание, часы, контакты, действия, отзывы.
 *
 * Экран — точка входа deep link'а `mahalla://place/{placeId}`, поэтому он
 * обязан переживать открытие «из ниоткуда»: id берётся из маршрута, всё
 * остальное грузит ViewModel.
 */
@Composable
fun PlaceDetailsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOrderClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onQueueClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onBookingClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onGamingClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onDoctorClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onCinemaClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onShopClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    onProductsClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    viewModel: PlaceDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PlaceDetailsEffect.NavigateBack -> onBack()

                is PlaceDetailsEffect.Dial -> context.startActivitySafely(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${effect.phone}")),
                )

                is PlaceDetailsEffect.OpenRoute -> context.startActivitySafely(
                    Intent(
                        Intent.ACTION_VIEW,
                        // geo: понимают и Яндекс.Карты, и Google Maps — выбор
                        // SDK для экрана 4.2 на это не влияет.
                        Uri.parse(
                            "geo:${effect.point.latitude},${effect.point.longitude}" +
                                "?q=${Uri.encode(effect.label)}",
                        ),
                    ),
                )

                // Заказ — вертикаль «Еда» (эпик 5), очередь — walk-in
                // (issue #96), бронь — запись на время (issue #97), зона —
                // игровые клубы (issue #98), врач — больницы (issue #99),
                // магазин — одежда (issue #108), товары — витрина аптеки
                // (issue #100).
                is PlaceDetailsEffect.OpenVertical -> when (effect.action) {
                    PlaceAction.Order -> onOrderClick(effect.placeId, effect.placeName)
                    PlaceAction.Queue -> onQueueClick(effect.placeId, effect.placeName)
                    PlaceAction.Booking -> onBookingClick(effect.placeId, effect.placeName)
                    PlaceAction.Gaming -> onGamingClick(effect.placeId, effect.placeName)
                    PlaceAction.Doctor -> onDoctorClick(effect.placeId, effect.placeName)
                    PlaceAction.Cinema -> onCinemaClick(effect.placeId, effect.placeName)
                    PlaceAction.Shop -> onShopClick(effect.placeId, effect.placeName)
                    PlaceAction.Products -> onProductsClick(effect.placeId, effect.placeName)
                    else -> Unit
                }
            }
        }
    }

    PlaceDetailsContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun PlaceDetailsContent(
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.data?.place?.name ?: stringResource(R.string.place_title),
            onBack = onBack,
        )
        ScreenStateHost(
            state = state.details,
            onRetry = { onEvent(PlaceDetailsEvent.Retry) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) { details ->
            DetailsList(details = details, state = state, onEvent = onEvent)
        }
    }

    // Комментарий удаляется навсегда и восстановить его нечем — спрашиваем.
    if (state.confirmDeleteComment != null) {
        MahallaDialog(
            title = stringResource(R.string.place_comment_delete_title),
            text = stringResource(R.string.place_comment_delete_message),
            confirmLabel = stringResource(R.string.place_comment_delete),
            onConfirm = { onEvent(PlaceDetailsEvent.CommentDeleteConfirmed) },
            onDismiss = { onEvent(PlaceDetailsEvent.CommentDeleteDismissed) },
            destructive = true,
        )
    }

    state.reviewForm?.let { form ->
        ReviewFormSheet(form = form, onEvent = onEvent)
    }

    state.reviewPendingDelete?.let {
        MahallaDialog(
            title = stringResource(R.string.place_review_delete_title),
            text = stringResource(R.string.place_review_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onEvent(PlaceDetailsEvent.ReviewDeleteConfirmed) },
            onDismiss = { onEvent(PlaceDetailsEvent.ReviewDeleteDismissed) },
            destructive = true,
        )
    }

    state.galleryDeletePending?.let {
        MahallaDialog(
            title = stringResource(R.string.place_gallery_delete_title),
            text = stringResource(R.string.place_gallery_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onEvent(PlaceDetailsEvent.GalleryPhotoDeleteConfirmed) },
            onDismiss = { onEvent(PlaceDetailsEvent.GalleryPhotoDeleteDismissed) },
            destructive = true,
        )
    }
}

@Composable
private fun DetailsList(
    details: PlaceDetails,
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(bottom = Spacing.gutter),
    ) {
        item(key = "gallery") {
            Gallery(
                photos = details.photos,
                placeName = details.place.name,
                userId = state.userId,
                onDeleteRequested = { onEvent(PlaceDetailsEvent.GalleryPhotoDeleteRequested(it)) },
            )
        }

        state.galleryDeleteFailure?.let { failure ->
            item(key = "gallery-failure") { ApiFailureText(failure = failure) }
        }

        item(key = "summary") { Summary(details = details, openNow = state.openNow) }

        item(key = "social") { SocialRow(state = state, onEvent = onEvent) }

        if (details.fromCache) {
            item(key = "cache-note") {
                Text(
                    text = stringResource(R.string.state_offline_cache),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }

        if (details.actions.isNotEmpty()) {
            item(key = "actions") { Actions(actions = details.actions, onEvent = onEvent) }
        }

        promotions(promotions = state.promotions)

        if (!details.description.isNullOrBlank()) {
            item(key = "description") {
                Text(
                    text = details.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        if (state.week.isNotEmpty()) {
            item(key = "hours") {
                Hours(
                    week = state.week,
                    today = state.today,
                    expanded = state.hoursExpanded,
                    onToggle = { onEvent(PlaceDetailsEvent.HoursToggled) },
                )
            }
        }

        contacts(details = details, onEvent = onEvent)

        reviews(state = state, onEvent = onEvent)

        comments(state = state, onEvent = onEvent)
    }
}

/**
 * Лайк и «Избранное» (issue #75).
 *
 * Состояние кнопок приходит с сервера отдельным запросом, и пока его нет,
 * рисовать «не нравится» нельзя: человек нажмёт — и снимет собственный лайк,
 * думая, что ставит его. Поэтому неизвестное состояние — это либо пустое
 * место (пока грузится), либо честный отказ с кнопкой «Повторить».
 */
@Composable
private fun SocialRow(
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val social = state.social
    if (social == null) {
        if (state.socialLoading) return
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = state.socialFailure?.userMessage()
                    ?: stringResource(R.string.place_social_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = { onEvent(PlaceDetailsEvent.SocialRetry) },
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MahallaIconButton(
                icon = if (social.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = pluralStringResource(
                    if (social.liked) R.plurals.place_social_unlike else R.plurals.place_social_like,
                    social.likes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    social.likes,
                ),
                onClick = { onEvent(PlaceDetailsEvent.LikeClicked) },
                enabled = !state.likePending,
                tint = if (social.liked) {
                    MaterialTheme.colorScheme.error
                } else {
                    LocalMahallaColors.current.fgMuted
                },
            )
            Text(
                text = social.likes.toString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onBackground,
            )
            MahallaButton(
                text = stringResource(
                    if (social.saved) R.string.place_social_saved else R.string.place_social_save,
                ),
                onClick = { onEvent(PlaceDetailsEvent.SaveClicked) },
                variant = if (social.saved) {
                    MahallaButtonVariant.Secondary
                } else {
                    MahallaButtonVariant.Ghost
                },
                state = ButtonState(enabled = !state.savePending),
                icon = if (social.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                fillWidth = false,
            )
        }

        // Отказ действия — текстом сервера (issue #34): кнопка, вернувшаяся в
        // прежнее состояние без объяснения, читается как сломанная.
        state.socialFailure?.let { failure ->
            Text(
                text = failure.userMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Комментарии — лента без оценки, отдельно от отзывов: отзыв ставит рейтинг
 * месту, комментарий отвечает соседям.
 *
 * Имени автора бэкенд не отдаёт (в `CommentResponse` только `userId`), поэтому
 * подпись у чужих записей общая, а «Удалить» появляется лишь у своих — там же,
 * где его разрешает сервер.
 */
private fun LazyListScope.comments(
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
) {
    item(key = "comments-header") {
        SectionHeader(title = stringResource(R.string.place_comments_title))
    }

    item(key = "comments-input") { CommentInput(state = state, onEvent = onEvent) }

    when (val comments = state.comments) {
        is ScreenState.Loading -> item(key = "comments-loading") {
            ListSkeleton(itemCount = COMMENT_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "comments-empty") {
            Text(
                text = stringResource(R.string.place_comments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        is ScreenState.Error -> item(key = "comments-error") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                Text(
                    text = comments.failure.userMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                MahallaButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onEvent(PlaceDetailsEvent.CommentsRetry) },
                    variant = MahallaButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }

        is ScreenState.Content -> {
            items(items = comments.data, key = { "comment-${it.id}" }) { comment ->
                CommentCard(
                    comment = comment,
                    deleting = state.deletingCommentId == comment.id,
                    onEvent = onEvent,
                )
            }
            if (state.hasMoreComments) {
                item(key = "comments-more") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                        MahallaButton(
                            text = stringResource(R.string.place_comments_show_more),
                            onClick = { onEvent(PlaceDetailsEvent.MoreCommentsRequested) },
                            variant = MahallaButtonVariant.Ghost,
                            state = ButtonState(loading = state.loadingMoreComments),
                        )
                        state.loadMoreCommentsFailure?.let { failure ->
                            Text(
                                text = failure.userMessage(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentInput(
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        MahallaTextField(
            value = state.commentDraft,
            onValueChange = { onEvent(PlaceDetailsEvent.CommentDraftChanged(it)) },
            label = stringResource(R.string.place_comment_hint),
            enabled = !state.sendingComment,
            singleLine = false,
        )
        MahallaButton(
            text = stringResource(R.string.place_comment_send),
            onClick = { onEvent(PlaceDetailsEvent.CommentSubmitted) },
            variant = MahallaButtonVariant.Secondary,
            state = ButtonState(
                enabled = state.canSubmitComment,
                loading = state.sendingComment,
            ),
            icon = Icons.AutoMirrored.Outlined.Send,
            fillWidth = false,
        )
        state.commentFailure?.let { failure ->
            Text(
                text = failure.userMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CommentCard(
    comment: PlaceComment,
    deleting: Boolean,
    onEvent: (PlaceDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (comment.isMine) {
                        R.string.place_comment_author_me
                    } else {
                        R.string.place_comment_author_other
                    },
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (comment.createdAt != null) {
                Text(
                    text = DateTimeFormatters.date(comment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }
        Text(
            text = comment.text,
            modifier = Modifier.padding(top = Spacing.item / 2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
        // Кнопка только у своих: чужой комментарий сервер удалить не даст, и
        // предлагать действие, которое заведомо ответит отказом, нельзя.
        if (comment.isMine) {
            MahallaButton(
                text = stringResource(R.string.place_comment_delete),
                onClick = { onEvent(PlaceDetailsEvent.CommentDeleteRequested(comment)) },
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = !deleting, loading = deleting),
                fillWidth = false,
            )
        }
    }
}

/**
 * Галерея (issue #60, #185): фотографии заведения лентой — из
 * `media/entity/{placeId}`, а обложка с логотипом только запасной вариант.
 *
 * Одна фотография занимает не всю ширину намеренно — край следующей говорит,
 * что ленту можно листать. Подпись для TalkBack одна на весь блок: читать
 * «фотографии такого-то» столько раз, сколько снимков, бессмысленно.
 *
 * В сетку идёт [MediaFile.thumbnailUrl] — полный размер сюда не грузим
 * (issue #185); все ссылки идут тем же `MahallaAsyncImage`, то есть через тот
 * же белый список схем, что и везде в приложении (issue #139).
 */
@Composable
private fun Gallery(
    photos: List<MediaFile>,
    placeName: String,
    userId: String?,
    onDeleteRequested: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ключ элемента LazyRow — сама ссылка, а дубликат ключа роняет список.
    // Бэкенд повторов и пустых строк не обещает, поэтому чистим здесь: то же
    // решение, что у SearchHistory.decode (PR #23).
    val shown = remember(photos) { photos.filter { it.url.isNotBlank() }.distinctBy { it.url } }
    if (shown.isEmpty()) return
    val description = stringResource(R.string.image_gallery_of, placeName)
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        items(items = shown, key = { it.url }) { photo ->
            GalleryPhoto(
                photo = photo,
                // Своё фото — то, у которого есть и id, и владелец, совпавший
                // с вошедшим: запасной вариант (id пуст) удалить нельзя, а
                // чужое бэкенд всё равно отклонит.
                isMine = photo.id.isNotBlank() &&
                    userId != null &&
                    photo.ownerId == userId,
                onDeleteRequested = { onDeleteRequested(photo) },
            )
        }
    }
}

@Composable
private fun GalleryPhoto(
    photo: MediaFile,
    isMine: Boolean,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        MahallaAsyncImage(
            url = photo.thumbnailUrl ?: photo.url,
            contentDescription = null,
            modifier = Modifier.size(
                width = MahallaComponentDefaults.galleryImageWidth,
                height = MahallaComponentDefaults.galleryImageHeight,
            ),
        )
        if (isMine) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.item / 2)
                    .background(GALLERY_DELETE_SCRIM, CircleShape),
            ) {
                MahallaIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.place_gallery_delete),
                    onClick = onDeleteRequested,
                    tint = Color.White,
                )
            }
        }
    }
}

/** Скрим под кнопкой удаления: без него белая иконка теряется на светлом фото. */
private val GALLERY_DELETE_SCRIM = Color.Black.copy(alpha = 0.45f)

@Composable
private fun Summary(
    details: PlaceDetails,
    openNow: Boolean?,
    modifier: Modifier = Modifier,
) {
    val place = details.place
    MahallaCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(place.category.labelRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            MahallaBadge(
                text = when (openNow) {
                    true -> stringResource(R.string.place_open_now)
                    false -> stringResource(R.string.place_closed_now)
                    null -> stringResource(R.string.place_hours_unknown)
                },
                tone = if (openNow == true) MahallaTone.Success else MahallaTone.Neutral,
            )
        }
        Row(
            modifier = Modifier.padding(top = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rating = RatingFormatter.format(place.rating, place.reviewCount)
            Text(
                text = if (rating != null) {
                    stringResource(
                        R.string.place_rating_with_reviews,
                        rating,
                        RatingFormatter.reviewCount(place.reviewCount),
                    )
                } else {
                    stringResource(R.string.place_no_rating)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = distanceLabel(place.distanceMeters),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

@Composable
private fun Actions(
    actions: List<PlaceAction>,
    onEvent: (PlaceDetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        actions.forEachIndexed { index, action ->
            MahallaButton(
                text = stringResource(action.labelRes()),
                onClick = { onEvent(PlaceDetailsEvent.ActionClicked(action)) },
                // Первое действие — основное; остальные не должны спорить с ним
                // за внимание.
                variant = if (index == 0) {
                    MahallaButtonVariant.Primary
                } else {
                    MahallaButtonVariant.Secondary
                },
                icon = action.icon(),
            )
        }
    }
}

@Composable
private fun Hours(
    week: List<OpeningHours>,
    today: DayOfWeek?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = if (expanded) week else week.filter { it.dayOfWeek == today }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.place_hours_title),
            actionLabel = stringResource(
                if (expanded) R.string.action_collapse else R.string.action_see_all,
            ),
            onAction = onToggle,
        )
        visible.forEach { day ->
            MahallaListItem(
                title = stringResource(day.dayOfWeek.labelRes()),
                subtitle = day.label(),
                showChevron = false,
            )
        }
    }
}

private fun LazyListScope.contacts(
    details: PlaceDetails,
    onEvent: (PlaceDetailsEvent) -> Unit,
) {
    val phone = details.contacts.phone
    val address = details.contacts.address
    if (phone == null && address == null) return

    item(key = "contacts-header") {
        SectionHeader(title = stringResource(R.string.place_contacts_title))
    }
    if (address != null) {
        item(key = "contacts-address") {
            MahallaListItem(
                title = address,
                leadingIcon = Icons.Outlined.Directions,
                showChevron = details.place.point != null,
                onClick = if (details.place.point != null) {
                    { onEvent(PlaceDetailsEvent.ActionClicked(PlaceAction.Route)) }
                } else {
                    null
                },
            )
        }
    }
    if (phone != null) {
        item(key = "contacts-phone") {
            MahallaListItem(
                title = phone,
                leadingIcon = Icons.Outlined.Call,
                onClick = { onEvent(PlaceDetailsEvent.ActionClicked(PlaceAction.Call)) },
            )
        }
    }
}

/**
 * Акции заведения (issue #104). Секции нет, пока акций нет: заголовок над
 * пустотой обещает скидку, которой не существует.
 *
 * Карточки здесь не нажимаются: вести им некуда — заведение уже открыто, а
 * экрана самой акции в приложении нет.
 */
private fun LazyListScope.promotions(promotions: List<Promotion>) {
    if (promotions.isEmpty()) return

    item(key = "promotions-header") {
        SectionHeader(title = stringResource(R.string.promotions_title))
    }
    items(items = promotions, key = { "promotion-${it.id}" }) { promotion ->
        PromotionCard(promotion = promotion)
    }
}

/**
 * Блок отзывов (issue #76). Заголовок и кнопка «оставить отзыв» показываются и
 * на пустом списке: раньше секция целиком исчезала, и оставить первый отзыв о
 * месте было негде.
 */
private fun LazyListScope.reviews(
    state: PlaceDetailsState,
    onEvent: (PlaceDetailsEvent) -> Unit,
) {
    val details = state.data ?: return
    val reviews = state.visibleReviews
    // Карточка из кэша про отзывы не знает ничего: пустой список здесь значит
    // «не загружали», а не «отзывов нет», и обещать форму тоже нельзя.
    if (details.fromCache && reviews.isEmpty()) return

    item(key = "reviews-header") {
        SectionHeader(title = stringResource(R.string.place_reviews_title))
    }

    if (state.canAddReview) {
        item(key = "reviews-add") {
            MahallaButton(
                text = stringResource(R.string.place_review_add),
                onClick = { onEvent(PlaceDetailsEvent.AddReviewClicked) },
                variant = MahallaButtonVariant.Secondary,
                icon = Icons.Outlined.RateReview,
            )
        }
    }

    // Отказ на удалении: текст сервера (issue #34), а не молчаливо оставшийся
    // на месте отзыв.
    state.reviewDeleteFailure?.let { failure ->
        item(key = "reviews-failure") { ApiFailureText(failure = failure) }
    }

    if (reviews.isEmpty()) {
        item(key = "reviews-empty") {
            Text(
                text = stringResource(R.string.place_reviews_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        return
    }

    items(items = reviews, key = { "review-${it.id}" }) { review ->
        ReviewCard(
            review = review,
            isMine = state.isMine(review),
            deleting = state.deletingReview,
            onDelete = { onEvent(PlaceDetailsEvent.ReviewDeleteRequested(review)) },
        )
    }
    if (state.hasHiddenReviews) {
        item(key = "reviews-more") {
            MahallaButton(
                text = stringResource(R.string.place_reviews_show_all),
                onClick = { onEvent(PlaceDetailsEvent.AllReviewsRequested) },
                variant = MahallaButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun ApiFailureText(failure: ApiFailure, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item / 2),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { server -> MahallaErrorDetails(server = server) }
    }
}

@Composable
private fun ReviewCard(
    review: Review,
    modifier: Modifier = Modifier,
    isMine: Boolean = false,
    deleting: Boolean = false,
    onDelete: () -> Unit = {},
) {
    MahallaCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Имени автора у бэкенда нет вовсе, ни под каким полем (issue
            // #192) — показываем «мой отзыв» либо честно «гость», а не
            // угаданное и всегда пустое имя.
            val author = if (isMine) {
                stringResource(R.string.place_review_mine)
            } else {
                stringResource(R.string.place_review_anonymous)
            }
            // Имя автора стоит той же строкой — аватар только рисуется,
            // TalkBack не должен читать его дважды.
            MahallaAvatar(url = null, name = author, contentDescription = null)
            Text(
                text = author,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = RatingFormatter.format(review.rating.toDouble()).orEmpty(),
                tone = MahallaTone.Accent,
            )
            // Удалять можно только свой отзыв: чужой не удалит и бэкенд, а
            // кнопка, которая всегда отвечает отказом, — обещание впустую.
            if (isMine) {
                MahallaIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.place_review_delete),
                    onClick = onDelete,
                    enabled = !deleting,
                )
            }
        }
        if (review.createdAt != null) {
            Text(
                text = DateTimeFormatters.date(review.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        Text(
            text = review.text,
            modifier = Modifier.padding(top = Spacing.item / 2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
        // Ответ заведения был в схеме и раньше, но не разбирался (issue #192).
        review.ownerReply?.let { reply ->
            Column(
                modifier = Modifier
                    .padding(top = Spacing.item / 2)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(Spacing.item),
            ) {
                Text(
                    text = stringResource(R.string.place_review_owner_reply_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = reply,
                    modifier = Modifier.padding(top = Spacing.item / 4),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Форма отзыва (issue #76): оценка и необязательный текст.
 *
 * Отказ сервера остаётся **в шторке**, рядом с набранным текстом: закрыть её
 * значило бы потерять и объяснение, и работу человека. Кнопка выключена, пока
 * оценки нет, а подпись под ней говорит, чего не хватает — выключенная кнопка
 * без причины читается как поломка.
 */
// Дефолтное значение sheetState в MahallaBottomSheet — экспериментальный API
// Material 3; opt-in нужен на стороне вызова.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewFormSheet(
    form: ReviewFormState,
    onEvent: (PlaceDetailsEvent) -> Unit,
) {
    MahallaBottomSheet(
        onDismiss = { onEvent(PlaceDetailsEvent.ReviewFormDismissed) },
        title = stringResource(R.string.place_review_form_title),
    ) {
        Text(
            text = stringResource(R.string.place_review_rating_label),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        MahallaRatingInput(
            rating = form.draft.rating,
            onRatingChange = { onEvent(PlaceDetailsEvent.ReviewRatingSelected(it)) },
            enabled = !form.submitting,
        )
        MahallaTextField(
            value = form.draft.text,
            onValueChange = { onEvent(PlaceDetailsEvent.ReviewTextChanged(it)) },
            label = stringResource(R.string.place_review_text_label),
            placeholder = stringResource(R.string.place_review_text_placeholder),
            supportingText = stringResource(
                R.string.place_review_text_counter,
                form.draft.trimmedText.length,
                ReviewDraft.MAX_TEXT_LENGTH,
            ),
            errorText = if (form.draft.isTooLong) {
                stringResource(R.string.place_review_text_too_long, ReviewDraft.MAX_TEXT_LENGTH)
            } else {
                null
            },
            enabled = !form.submitting,
            singleLine = false,
        )
        form.failure?.let { failure -> ApiFailureText(failure = failure) }
        MahallaButton(
            text = stringResource(R.string.place_review_submit),
            onClick = { onEvent(PlaceDetailsEvent.ReviewSubmitted) },
            state = ButtonState(enabled = form.draft.canSubmit, loading = form.submitting),
        )
        if (!form.draft.isRated) {
            ButtonCaption(text = stringResource(R.string.place_review_rating_required))
        }
    }
}

@Composable
private fun OpeningHours.label(): String = when {
    isAroundTheClock -> stringResource(R.string.place_hours_around_the_clock)
    isDayOff -> stringResource(R.string.place_hours_day_off)
    // Ветка достижима только когда обе границы заданы — это проверено выше
    // через isDayOff, но компилятор об этом не знает.
    else -> "${HOUR_FORMAT.format(opensAt!!)} – ${HOUR_FORMAT.format(closesAt!!)}"
}

private fun PlaceAction.labelRes(): Int = when (this) {
    PlaceAction.Queue -> R.string.place_action_queue
    PlaceAction.Booking -> R.string.place_action_booking
    PlaceAction.Gaming -> R.string.place_action_gaming
    PlaceAction.Doctor -> R.string.place_action_doctor
    PlaceAction.Cinema -> R.string.place_action_cinema
    PlaceAction.Order -> R.string.place_action_order
    PlaceAction.Shop -> R.string.place_action_shop
    PlaceAction.Products -> R.string.place_action_products
    PlaceAction.Call -> R.string.place_action_call
    PlaceAction.Route -> R.string.place_action_route
}

private fun PlaceAction.icon(): ImageVector = when (this) {
    PlaceAction.Queue -> Icons.Outlined.ConfirmationNumber
    PlaceAction.Booking -> Icons.Outlined.EventAvailable
    PlaceAction.Gaming -> Icons.Outlined.SportsEsports
    PlaceAction.Doctor -> Icons.Outlined.MedicalServices
    PlaceAction.Cinema -> Icons.Outlined.Movie
    PlaceAction.Order -> Icons.Outlined.ShoppingBag
    PlaceAction.Shop -> Icons.Outlined.Storefront
    PlaceAction.Products -> Icons.Outlined.LocalPharmacy
    PlaceAction.Call -> Icons.Outlined.Call
    PlaceAction.Route -> Icons.Outlined.Directions
}

private fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.day_monday
    DayOfWeek.TUESDAY -> R.string.day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.day_wednesday
    DayOfWeek.THURSDAY -> R.string.day_thursday
    DayOfWeek.FRIDAY -> R.string.day_friday
    DayOfWeek.SATURDAY -> R.string.day_saturday
    DayOfWeek.SUNDAY -> R.string.day_sunday
}

/**
 * Набирать номер и строить маршрут умеют не все устройства (и не все
 * оболочки). Отсутствие приложения-обработчика — не повод падать.
 */
private fun android.content.Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (notFound: ActivityNotFoundException) {
        // Обработчика нет — молча ничего не делаем, экран остаётся на месте.
    }
}

private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val GALLERY_HEIGHT = 120.dp
private const val MAX_GALLERY_PREVIEW = 3
private const val COMMENT_SKELETONS = 2
