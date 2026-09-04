package uz.mahalla.feature.discovery.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.ui.CategoryGrid
import uz.mahalla.feature.discovery.ui.SearchEntryButton
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.feature.notifications.ui.NotificationsBadgeAction
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.promotions.ui.PromotionCard
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Главная (эпик 4.1): категории, «рядом», рекомендации.
 *
 * Экран собран из компонентов кита (эпик 2) и ничего не решает сам: состояние
 * и переходы — во ViewModel, здесь только отрисовка и события.
 */
@Composable
fun DiscoveryHomeScreen(
    onPlaceClick: (String) -> Unit,
    onSearchClick: (PlaceCategory?) -> Unit,
    onMapClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoveryHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiscoveryHomeEffect.OpenPlace -> onPlaceClick(effect.placeId)
                is DiscoveryHomeEffect.OpenSearch -> onSearchClick(effect.category)
                DiscoveryHomeEffect.OpenMap -> onMapClick()
            }
        }
    }

    DiscoveryHomeContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        // Бейдж непрочитанного считает своя ViewModel (issue #81): к каталогу
        // он отношения не имеет и обновляется на каждом возврате на главную.
        actions = { NotificationsBadgeAction(onClick = onNotificationsClick) },
    )
}

/** Разделено ради превью и читаемости: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun DiscoveryHomeContentScreen(
    state: DiscoveryHomeState,
    onEvent: (DiscoveryHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.discovery_title), actions = actions)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(DiscoveryHomeEvent.Refresh) },
        ) {
            HomeList(state = state, onEvent = onEvent)
        }
    }
}

/**
 * Главная — один список, а не `ScreenStateHost` вокруг всего экрана.
 *
 * Поиск, категории и акции (issue #104) живут своей жизнью: акции приходят
 * другой ручкой, и пустой каталог — а сейчас на стенде он именно пуст
 * (issue #53) — не должен уносить их с экрана вместе с собой. Заодно на пустой
 * выдаче и на ошибке остаются строка поиска и плитка категорий: раньше
 * `ScreenStateHost` подменял их целиком, и уйти с пустой главной было некуда.
 */
@Composable
private fun HomeList(
    state: DiscoveryHomeState,
    onEvent: (DiscoveryHomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.gutter),
    ) {
        item(key = "search") {
            SearchEntryButton(
                onClick = { onEvent(DiscoveryHomeEvent.SearchClicked) },
                onMapClick = { onEvent(DiscoveryHomeEvent.MapClicked) },
            )
        }

        item(key = "categories") {
            CategoryGrid(
                categories = state.categories,
                onCategoryClick = { onEvent(DiscoveryHomeEvent.CategoryClicked(it)) },
            )
        }

        promotionSection(promotions = state.promotions, onEvent = onEvent)

        catalog(state = state, onEvent = onEvent)
    }
}

/**
 * Каталог: скелетон, пусто, ошибка или две секции мест.
 *
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой (issue #62).
 */
private fun LazyListScope.catalog(
    state: DiscoveryHomeState,
    onEvent: (DiscoveryHomeEvent) -> Unit,
) {
    when (val content = state.content) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton() }

        is ScreenState.Empty -> item(key = "empty") { EmptyState() }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = content.failure,
                onRetry = { onEvent(DiscoveryHomeEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            if (content.data.fromCache) {
                item(key = "cache-note") { CacheNote() }
            }

            placeSection(
                key = "nearby",
                titleRes = R.string.discovery_section_nearby,
                places = content.data.nearby,
                onEvent = onEvent,
            )

            placeSection(
                key = "recommended",
                titleRes = R.string.discovery_section_recommended,
                places = content.data.recommended,
                onEvent = onEvent,
            )
        }
    }
}

/**
 * Блок акций платформы (issue #104). Пустой список секцию скрывает целиком:
 * заголовок над пустотой обещает то, чего нет.
 */
private fun LazyListScope.promotionSection(
    promotions: List<Promotion>,
    onEvent: (DiscoveryHomeEvent) -> Unit,
) {
    if (promotions.isEmpty()) return

    item(key = "promotions-header") {
        SectionHeader(title = stringResource(R.string.promotions_title))
    }
    items(items = promotions, key = { "promotion-${it.id}" }) { promotion ->
        PromotionCard(
            promotion = promotion,
            onClick = { onEvent(DiscoveryHomeEvent.PromotionClicked(promotion.id)) },
        )
    }
}

/**
 * Отказ каталога внутри списка: текст сервера, подробности и повтор
 * (issue #34). `ApiErrorState` здесь не годится — он прокручивается сам
 * (см. [catalog]). Такой же блок есть у уведомлений и кошелька; свести их в
 * один компонент кита — отдельная уборка, не входившая в issue #104.
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
 * Секция блоками, а не вложенным списком: горизонтальная карусель на 6
 * карточек хуже читается при крупном шрифте, чем вертикальный список.
 */
private fun LazyListScope.placeSection(
    key: String,
    @StringRes titleRes: Int,
    places: List<Place>,
    onEvent: (DiscoveryHomeEvent) -> Unit,
) {
    if (places.isEmpty()) return

    item(key = "$key-header") {
        SectionHeader(
            title = stringResource(titleRes),
            actionLabel = stringResource(R.string.action_see_all),
            onAction = { onEvent(DiscoveryHomeEvent.SearchClicked) },
        )
    }
    items(items = places, key = { "$key-${it.id}" }) { place ->
        PlaceCard(
            place = place.toCardUi(),
            onClick = { onEvent(DiscoveryHomeEvent.PlaceClicked(place.id)) },
        )
    }
}

/** Данные из кэша подписываются явно — иначе устаревшее выглядит свежим. */
@Composable
private fun CacheNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.state_offline_cache),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalMahallaColors.current.fgMuted,
    )
}
