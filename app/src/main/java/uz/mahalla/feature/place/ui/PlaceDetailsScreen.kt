package uz.mahalla.feature.place.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
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
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.ui.distanceLabel
import uz.mahalla.feature.place.domain.OpeningHours
import uz.mahalla.feature.place.domain.PlaceAction
import uz.mahalla.feature.place.domain.PlaceDetails
import uz.mahalla.feature.place.domain.Review
import uz.mahalla.feature.place.domain.ReviewDraft
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
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
                // (issue #96); бронь ждёт своего эпика.
                is PlaceDetailsEffect.OpenVertical -> when (effect.action) {
                    PlaceAction.Order -> onOrderClick(effect.placeId, effect.placeName)
                    PlaceAction.Queue -> onQueueClick(effect.placeId, effect.placeName)
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
        item(key = "gallery") { Gallery(photoCount = details.photos.size) }

        item(key = "summary") { Summary(details = details, openNow = state.openNow) }

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
    }
}

/**
 * Галерея — пока скелетоны по числу фото: загрузчика изображений в проекте
 * ещё нет (Coil появится вместе с медиа-эпиком), а рисовать пустоту вместо
 * известного количества снимков хуже, чем показать их места.
 */
@Composable
private fun Gallery(photoCount: Int, modifier: Modifier = Modifier) {
    if (photoCount == 0) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        repeat(photoCount.coerceAtMost(MAX_GALLERY_PREVIEW)) {
            Box(modifier = Modifier.weight(1f)) {
                SkeletonBox(modifier = Modifier.fillMaxWidth(), height = GALLERY_HEIGHT)
            }
        }
    }
}

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
        item(key = "reviews-failure") { ReviewFailure(failure = failure) }
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
private fun ReviewFailure(failure: ApiFailure, modifier: Modifier = Modifier) {
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
            Text(
                text = if (isMine) {
                    stringResource(R.string.place_review_mine)
                } else {
                    review.author.ifBlank { stringResource(R.string.place_review_anonymous) }
                },
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
        form.failure?.let { failure -> ReviewFailure(failure = failure) }
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
    PlaceAction.Order -> R.string.place_action_order
    PlaceAction.Call -> R.string.place_action_call
    PlaceAction.Route -> R.string.place_action_route
}

private fun PlaceAction.icon(): ImageVector = when (this) {
    PlaceAction.Queue -> Icons.Outlined.ConfirmationNumber
    PlaceAction.Booking -> Icons.Outlined.EventAvailable
    PlaceAction.Order -> Icons.Outlined.ShoppingBag
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
