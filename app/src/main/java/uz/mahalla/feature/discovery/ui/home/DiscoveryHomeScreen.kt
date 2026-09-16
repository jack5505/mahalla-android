package uz.mahalla.feature.discovery.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import uz.mahalla.core.ui.components.MahallaDivider
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.PlaceCard
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.text.fullLabelRes
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
import java.time.Instant

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
    onTicketClick: (placeId: String, placeName: String) -> Unit = { _, _ -> },
    viewModel: DiscoveryHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Талон могли отменить на экране очереди, а срок жизни его чисел — две
    // минуты: возврат на главную обязан перечитать его из хранилища.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(DiscoveryHomeEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiscoveryHomeEffect.OpenPlace -> onPlaceClick(effect.placeId)
                is DiscoveryHomeEffect.OpenSearch -> onSearchClick(effect.category)
                DiscoveryHomeEffect.OpenMap -> onMapClick()
                is DiscoveryHomeEffect.OpenTicket ->
                    onTicketClick(effect.placeId, effect.placeName)
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
        MahallaTopBar(
            title = stringResource(R.string.discovery_title),
            brandMark = true,
            meta = state.openedAt?.let { headerMeta(it) },
            actions = actions,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(DiscoveryHomeEvent.Refresh) },
        ) {
            HomeList(
                state = state,
                onEvent = onEvent,
                onFreelancersClick = onFreelancersClick,
            )
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
    onFreelancersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.gutter),
    ) {
        // Фокус-карточка — первым блоком (макет 1a/1d): она отвечает на
        // вопрос «что мне сейчас», и всё остальное на экране — уже поиск.
        //
        // Ячейка заводится только когда карточке есть что сказать: пустая
        // всё равно получила бы от `spacedBy` свои 20dp, и над строкой поиска
        // висела бы дыра — на холодном старте (каталог ещё грузится) и ночью,
        // когда рядом ничего не открыто.
        if (state.ticket != null || state.nearestOpenPlace != null) {
            item(key = "focus") {
                FocusCard(
                    ticket = state.ticket,
                    queueInfoIsCurrent = state.ticketQueueInfoIsCurrent,
                    nearestOpenPlace = state.nearestOpenPlace,
                    onOpenTicket = { onEvent(DiscoveryHomeEvent.TicketClicked) },
                    onOpenPlace = { onEvent(DiscoveryHomeEvent.PlaceClicked(it)) },
                )
            }
        }

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
    // Линии между строками рисует список, а не сама строка: под последней
    // она не нужна (макет 1a).
    itemsIndexed(items = places, key = { _, place -> "$key-${place.id}" }) { index, place ->
        Column {
            if (index > 0) MahallaDivider()
            PlaceCard(
                place = place.toCardUi(),
                onClick = { onEvent(DiscoveryHomeEvent.PlaceClicked(place.id)) },
            )
        }
    }
}

/**
 * Мета шапки «9:30 · вторник» (общая шапка макета). День недели — строчными:
 * в макете он подпись, а не заголовок; ресурсы `day_*` заглавные, потому что
 * их же показывает таблица часов на карточке места.
 */
@Composable
private fun headerMeta(openedAt: Instant): String {
    val weekday = stringResource(openedAt.atZone(DateTimeFormatters.AppZone).dayOfWeek.fullLabelRes())
    return stringResource(
        R.string.text_joined_with_dot,
        DateTimeFormatters.time(openedAt),
        weekday.lowercase(),
    )
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
