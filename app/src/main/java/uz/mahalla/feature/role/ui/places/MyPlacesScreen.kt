package uz.mahalla.feature.role.ui.places

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * «Мои заведения» (issue #94): список того, что человек зарегистрировал, с
 * решением модерации по каждому.
 *
 * До этого экрана судьбу заявки в приложении было не видно вовсе: она уходила
 * `PENDING` и пропадала.
 */
@Composable
fun MyPlacesScreen(
    onPlaceClick: (String) -> Unit,
    onRegisterPlace: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyPlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MyPlacesEffect.OpenPlace -> onPlaceClick(effect.placeId)
                MyPlacesEffect.OpenProviderForm -> onRegisterPlace()
            }
        }
    }

    // Модерация могла принять решение, пока приложение было в фоне — а сюда
    // приходят ровно за этим.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MyPlacesEvent.ScreenResumed)
    }

    MyPlacesContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MyPlacesContentScreen(
    state: MyPlacesState,
    onEvent: (MyPlacesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.my_places_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(MyPlacesEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ переключателя — над списком, а не вместо него:
                // заведения уже на экране, и прятать их незачем.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { InlineFailure(failure = failure) }
                }
                myPlaceItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.myPlaceItems(
    state: MyPlacesState,
    onEvent: (MyPlacesEvent) -> Unit,
) {
    when (val places = state.places) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Пустой список ведёт в анкету продавца: экран без выхода на «а как
        // зарегистрировать?» отвечал бы только «у вас ничего нет».
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.my_places_empty_title),
                description = stringResource(R.string.my_places_empty_description),
                icon = Icons.Outlined.Storefront,
                actionLabel = stringResource(R.string.my_places_register),
                onAction = { onEvent(MyPlacesEvent.RegisterPlaceRequested) },
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = places.failure,
                onRetry = { onEvent(MyPlacesEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(places.data, key = MyPlace::id) { place ->
                MyPlaceCard(
                    place = place,
                    pending = state.pendingPlaceId == place.id,
                    // Пока идёт запрос по одной строке, остальные не трогаем:
                    // ответы приехали бы на список, которого уже нет.
                    enabled = state.pendingPlaceId == null,
                    onEvent = onEvent,
                )
            }
            item(key = "register") {
                MahallaButton(
                    text = stringResource(R.string.my_places_register),
                    onClick = { onEvent(MyPlacesEvent.RegisterPlaceRequested) },
                    variant = MahallaButtonVariant.Secondary,
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = places.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/**
 * Карточка своего заведения: имя, категория, статус модерации, адрес и
 * переключатель «открыто сейчас».
 *
 * Кликабельна только та, у которой есть карточка в каталоге
 * ([MyPlace.isOpenable]): у заявки на модерации `GET places/{id}` ответил бы
 * «не найдено», а нажатие в ошибку хуже строки, которая не нажимается. Взамен
 * такая карточка объясняет словами, чего ждать.
 */
@Composable
private fun MyPlaceCard(
    place: MyPlace,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (MyPlacesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(
        modifier = modifier,
        onClick = if (place.isOpenable) {
            { onEvent(MyPlacesEvent.PlaceClicked(place.id)) }
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = place.name.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.my_places_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(place.status.labelRes()),
                tone = place.status.tone(),
            )
        }

        Text(
            text = stringResource(place.category.labelRes),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )

        place.address?.let { address ->
            Text(
                text = address,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        // Роль показываем только тогда, когда она объясняет, почему действий
        // меньше, чем у владельца.
        place.staffRole.labelRes()?.let { labelRes ->
            Text(
                text = stringResource(labelRes),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        RatingFormatter.format(place.rating, place.ratingCount)?.let { rating ->
            Text(
                text = stringResource(
                    R.string.place_rating_with_reviews,
                    rating,
                    RatingFormatter.reviewCount(place.ratingCount),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        // Заявка на модерации объясняет себя словами: иначе карточка, которая
        // не нажимается и ничего не предлагает, читается как сломанная.
        place.status.hintRes()?.let { hintRes ->
            Text(
                text = stringResource(hintRes),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        if (place.canToggleAvailability) {
            MahallaSwitchRow(
                title = stringResource(R.string.my_places_available),
                checked = place.isAvailable,
                onCheckedChange = { onEvent(MyPlacesEvent.AvailabilityToggled(place.id)) },
                description = stringResource(R.string.my_places_available_description),
                // Пока идёт запрос, переключатель занят: второй тап заводил бы
                // второй переворот флага, и результат зависел бы от порядка
                // ответов.
                enabled = enabled && !pending,
            )
        }
    }
}

/**
 * Отказ внутри списка: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он прокручивается сам (см. [myPlaceItems]).
 */
@Composable
private fun InlineFailure(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
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
        if (onRetry != null) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: MyPlacesState,
    itemCount: Int,
    onEvent: (MyPlacesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(MyPlacesEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(MyPlacesEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

/**
 * Подписи статусов. Домен про Android не знает, поэтому сопоставление живёт
 * здесь — как у роли в [uz.mahalla.feature.role.ui.labelRes].
 *
 * У [PlaceModerationStatus.Unknown] своя строка, а не «на модерации»:
 * незнакомый статус бэкенда не значит «ждём проверку».
 */
@StringRes
private fun PlaceModerationStatus.labelRes(): Int = when (this) {
    PlaceModerationStatus.Pending -> R.string.my_places_status_pending
    PlaceModerationStatus.Active -> R.string.my_places_status_active
    PlaceModerationStatus.Suspended -> R.string.my_places_status_suspended
    PlaceModerationStatus.Closed -> R.string.my_places_status_closed
    PlaceModerationStatus.Unknown -> R.string.my_places_status_unknown
}

/** Тон бейджа: «на модерации» — ожидание, а не отказ. */
private fun PlaceModerationStatus.tone(): MahallaTone = when (this) {
    PlaceModerationStatus.Active -> MahallaTone.Success
    PlaceModerationStatus.Pending -> MahallaTone.Warning
    PlaceModerationStatus.Suspended, PlaceModerationStatus.Closed -> MahallaTone.Error
    PlaceModerationStatus.Unknown -> MahallaTone.Neutral
}

/** Объяснение статуса — только там, где человеку нужно знать, что дальше. */
@StringRes
private fun PlaceModerationStatus.hintRes(): Int? = when (this) {
    PlaceModerationStatus.Pending -> R.string.my_places_status_pending_hint
    PlaceModerationStatus.Suspended -> R.string.my_places_status_suspended_hint
    PlaceModerationStatus.Active, PlaceModerationStatus.Closed,
    PlaceModerationStatus.Unknown,
    -> null
}

/** Владельцу роль не показываем: она и так очевидна из того, что он может. */
@StringRes
private fun PlaceStaffRole.labelRes(): Int? = when (this) {
    PlaceStaffRole.Manager -> R.string.my_places_role_manager
    PlaceStaffRole.Staff -> R.string.my_places_role_staff
    PlaceStaffRole.Owner, PlaceStaffRole.Unknown -> null
}

private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun MyPlacesScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyPlacesContentScreen(
            state = MyPlacesState(
                places = ScreenState.Content(
                    listOf(
                        MyPlace(
                            id = "p-1",
                            name = "Osh Markazi",
                            category = PlaceCategory.Food,
                            status = PlaceModerationStatus.Active,
                            address = "Chilonzor, 12-kvartal",
                            isAvailable = true,
                            rating = 4.6,
                            ratingCount = 128,
                            staffRole = PlaceStaffRole.Owner,
                        ),
                        MyPlace(
                            id = "p-2",
                            name = "Barber Studio",
                            category = PlaceCategory.Master,
                            status = PlaceModerationStatus.Pending,
                            address = "Yunusobod, 4-daha",
                            staffRole = PlaceStaffRole.Manager,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
