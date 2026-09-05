package uz.mahalla.feature.discovery.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handyman
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
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.ui.CategoryGrid
import uz.mahalla.feature.discovery.ui.SearchEntryButton
import uz.mahalla.feature.discovery.ui.toCardUi
import uz.mahalla.feature.notifications.ui.NotificationsBadgeAction
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
    onFreelancersClick: () -> Unit,
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
        // Мастера — не заведения, и через ViewModel каталога этот переход не
        // идёт: у него другой источник данных и другой экран (issue #107).
        onFreelancersClick = onFreelancersClick,
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
    onFreelancersClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.discovery_title), actions = actions)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(DiscoveryHomeEvent.Refresh) },
        ) {
            ScreenStateHost(
                state = state.content,
                onRetry = { onEvent(DiscoveryHomeEvent.Retry) },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
                loading = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                        SearchEntryButton(
                            onClick = { onEvent(DiscoveryHomeEvent.SearchClicked) },
                            onMapClick = { onEvent(DiscoveryHomeEvent.MapClicked) },
                        )
                        CategoryGrid(
                            categories = state.categories,
                            onCategoryClick = { onEvent(DiscoveryHomeEvent.CategoryClicked(it)) },
                        )
                        ListSkeleton()
                    }
                },
            ) { content ->
                HomeList(
                    content = content,
                    categories = state.categories,
                    onEvent = onEvent,
                    onFreelancersClick = onFreelancersClick,
                )
            }
        }
    }
}

@Composable
private fun HomeList(
    content: DiscoveryHomeContent,
    categories: List<PlaceCategory>,
    onEvent: (DiscoveryHomeEvent) -> Unit,
    onFreelancersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(bottom = Spacing.gutter),
    ) {
        item(key = "search") {
            SearchEntryButton(
                onClick = { onEvent(DiscoveryHomeEvent.SearchClicked) },
                onMapClick = { onEvent(DiscoveryHomeEvent.MapClicked) },
            )
        }

        if (content.fromCache) {
            item(key = "cache-note") { CacheNote() }
        }

        item(key = "categories") {
            CategoryGrid(
                categories = categories,
                onCategoryClick = { onEvent(DiscoveryHomeEvent.CategoryClicked(it)) },
            )
        }

        // Мастера-фрилансеры (issue #107). Отдельной строкой, а не плиткой в
        // сетке категорий: плитка ведёт в каталог заведений, а мастер —
        // человек с собственным профилем и услугами, и список у него свой.
        item(key = "freelancers") {
            MahallaListItem(
                title = stringResource(R.string.freelancers_title),
                subtitle = stringResource(R.string.freelancers_home_subtitle),
                leadingIcon = Icons.Outlined.Handyman,
                onClick = onFreelancersClick,
            )
        }

        placeSection(
            key = "nearby",
            titleRes = R.string.discovery_section_nearby,
            places = content.nearby,
            onEvent = onEvent,
        )

        placeSection(
            key = "recommended",
            titleRes = R.string.discovery_section_recommended,
            places = content.recommended,
            onEvent = onEvent,
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
