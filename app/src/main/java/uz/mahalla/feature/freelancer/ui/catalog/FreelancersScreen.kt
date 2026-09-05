package uz.mahalla.feature.freelancer.ui.catalog

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
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSearchField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Каталог мастеров (issue #107).
 *
 * Мастер — **не заведение**, поэтому и список свой, а не выдача каталога:
 * у карточки другие данные (специальность, ставка за час, доступность), и
 * ведёт она не на `PlaceRoute`, а в профиль фрилансера.
 */
@Composable
fun FreelancersScreen(
    onFreelancerClick: (String, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FreelancersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FreelancersEffect.OpenFreelancer ->
                    onFreelancerClick(effect.freelancerId, effect.name)
            }
        }
    }

    FreelancersContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FreelancersContent(
    state: FreelancersState,
    onEvent: (FreelancersEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.freelancers_title), onBack = onBack)
        MahallaSearchField(
            query = state.profession,
            onQueryChange = { onEvent(FreelancersEvent.ProfessionChanged(it)) },
            modifier = Modifier.padding(horizontal = Spacing.gutter),
            placeholder = stringResource(R.string.freelancers_search_hint),
            onSearch = { onEvent(FreelancersEvent.ProfessionSubmitted) },
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(FreelancersEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                freelancerItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.freelancerItems(
    state: FreelancersState,
    onEvent: (FreelancersEvent) -> Unit,
) {
    when (val freelancers = state.freelancers) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Пустой каталог — ответ сервера, а не поломка: на стенде сегодня
        // мастеров нет вовсе (issue #53). Текст различает «никого нет» и
        // «никого не нашлось по фильтру»: во втором случае помогает правка
        // строки поиска, а в первом ждать нечего.
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.freelancers_empty_title),
                description = if (state.profession.isBlank()) {
                    stringResource(R.string.freelancers_empty_description)
                } else {
                    stringResource(R.string.freelancers_empty_filtered)
                },
                icon = Icons.Outlined.Handyman,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = freelancers.failure,
                onRetry = { onEvent(FreelancersEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(freelancers.data, key = Freelancer::id) { freelancer ->
                FreelancerCard(
                    freelancer = freelancer,
                    onClick = {
                        onEvent(FreelancersEvent.FreelancerClicked(freelancer.id))
                    },
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = freelancers.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun FreelancerCard(
    freelancer: Freelancer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = freelancer.name.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.freelancer_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Занятость показывается только когда мастер занят: бейдж
            // «свободен» у каждой строки списка не сообщает ничего.
            if (!freelancer.isAvailable) {
                MahallaBadge(
                    text = stringResource(R.string.freelancer_unavailable),
                    tone = MahallaTone.Neutral,
                )
            }
        }

        freelancer.profession?.let { profession ->
            Text(
                text = profession,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        FreelancerMeta(freelancer = freelancer, modifier = Modifier.padding(top = Spacing.item))

        freelancer.city?.let { city ->
            Text(
                text = city,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

/** Ставка, опыт и рейтинг — то, по чему мастера и выбирают. */
@Composable
internal fun FreelancerMeta(freelancer: Freelancer, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    val parts = buildList {
        freelancer.rateText()?.let(::add)
        freelancer.experienceYears?.let { years ->
            add(pluralStringResource(R.plurals.freelancer_experience, years, years))
        }
        add(freelancer.ratingText())
    }
    Text(
        text = parts.joinToString(separator = SEPARATOR),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
        color = colors.fgMuted,
    )
}

/** Ставка за час; ноль — «не названа», и тогда строки просто нет. */
@Composable
internal fun Freelancer.rateText(): String? = hourlyRateSum.takeIf { it > 0 }?.let { rate ->
    stringResource(
        R.string.freelancer_rate,
        MoneyFormatter.withCurrency(rate, stringResource(R.string.currency_uzs)),
    )
}

/** «4,8 · 12 отзывов» либо «Не оценён»: `0,0` читалось бы как плохая оценка. */
@Composable
internal fun Freelancer.ratingText(): String {
    val rating = RatingFormatter.format(ratingAvg, ratingCount)
        ?: return stringResource(R.string.place_no_rating)
    return stringResource(
        R.string.place_rating_with_reviews,
        rating,
        RatingFormatter.reviewCount(ratingCount),
    )
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: FreelancersState,
    itemCount: Int,
    onEvent: (FreelancersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(FreelancersEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(FreelancersEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

private const val SEPARATOR = " · "
private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun FreelancersPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FreelancersContent(
            state = FreelancersState(
                freelancers = ScreenState.Content(
                    listOf(
                        Freelancer(
                            id = "f-1",
                            name = "Aziz Karimov",
                            profession = "Santexnik",
                            city = "Toshkent",
                            hourlyRateSum = 80_000,
                            experienceYears = 7,
                            ratingAvg = 4.8,
                            ratingCount = 12,
                        ),
                        Freelancer(
                            id = "f-2",
                            name = "Dilshod Rahimov",
                            profession = "Elektrik",
                            isAvailable = false,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
