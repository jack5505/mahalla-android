package uz.mahalla.feature.discovery.ui.home

import androidx.compose.runtime.Immutable
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.promotions.domain.Promotion

/** Содержимое главной (эпик 4.1): блоки «рядом» и «рекомендации». */
@Immutable
data class DiscoveryHomeContent(
    val nearby: List<Place> = emptyList(),
    val recommended: List<Place> = emptyList(),
    /** Данные из офлайн-кэша — на экране это подписано явно. */
    val fromCache: Boolean = false,
)

data class DiscoveryHomeState(
    val content: ScreenState<DiscoveryHomeContent> = ScreenState.Loading,
    /**
     * Pull-to-refresh отдельно от [ScreenState.Loading]: список остаётся на
     * месте, скелетон его не подменяет.
     */
    val isRefreshing: Boolean = false,
    val categories: List<PlaceCategory> = PlaceCategory.selectable,
    /**
     * Акции платформы (issue #104). Отдельно от [content]: это другая ручка,
     * и каталог с акциями не должны валить друг друга — пустой каталог не
     * повод спрятать акции, а отказ акций не повод потерять выдачу.
     *
     * Пустой список — секции нет: рисовать заголовок над пустотой хуже, чем
     * не рисовать ничего.
     */
    val promotions: List<Promotion> = emptyList(),
) : UiState

sealed interface DiscoveryHomeEvent : UiEvent {
    data object Retry : DiscoveryHomeEvent
    data object Refresh : DiscoveryHomeEvent
    data class CategoryClicked(val category: PlaceCategory) : DiscoveryHomeEvent
    data class PlaceClicked(val placeId: String) : DiscoveryHomeEvent
    data object SearchClicked : DiscoveryHomeEvent
    data object MapClicked : DiscoveryHomeEvent

    /** Акция (issue #104): куда она ведёт, решает ViewModel. */
    data class PromotionClicked(val promotionId: String) : DiscoveryHomeEvent
}

sealed interface DiscoveryHomeEffect : UiEffect {
    data class OpenPlace(val placeId: String) : DiscoveryHomeEffect

    /** `null` — поиск без предвыбранной категории. */
    data class OpenSearch(val category: PlaceCategory?) : DiscoveryHomeEffect

    data object OpenMap : DiscoveryHomeEffect
}
