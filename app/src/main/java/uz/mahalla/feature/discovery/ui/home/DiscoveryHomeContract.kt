package uz.mahalla.feature.discovery.ui.home

import androidx.compose.runtime.Immutable
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.queue.domain.WalkInTicket

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
    /**
     * Талон для фокус-карточки (макет 1d): живой талон вошедшего, если он
     * есть. Читается локально — ручки чтения талона у бэкенда нет вовсе
     * (`WalkInApi`), и хранилище помнит последнее известное состояние.
     */
    val ticket: WalkInTicket? = null,
    /**
     * Свежи ли позиция и ожидание в [ticket] — правило живёт в домене
     * (`WalkInTicket.showsQueueInfo`, две минуты). Считается на загрузке и на
     * каждом возврате на экран: число из прошлого часа выдавать за текущее
     * нельзя, а пересчитать его нечем.
     */
    val ticketQueueInfoIsCurrent: Boolean = false,
) : UiState {

    /**
     * Ближайшее открытое место — то, о чём фокус-карточка говорит, пока
     * талона нет. Закрытые не годятся: карточка отвечает на вопрос «куда
     * можно сейчас».
     */
    val nearestOpenPlace: Place?
        get() = (content as? ScreenState.Content)?.data?.nearby?.firstOrNull(Place::isOpenNow)
}

sealed interface DiscoveryHomeEvent : UiEvent {
    data object Retry : DiscoveryHomeEvent
    data object Refresh : DiscoveryHomeEvent
    data class CategoryClicked(val category: PlaceCategory) : DiscoveryHomeEvent
    data class PlaceClicked(val placeId: String) : DiscoveryHomeEvent
    data object SearchClicked : DiscoveryHomeEvent
    data object MapClicked : DiscoveryHomeEvent

    /**
     * Экран вернулся на передний план. Каталог при этом не перезапрашивается —
     * перечитывается только талон: пока приложение было в фоне, его могли
     * отменить с другого экрана, а числа в нём — устареть.
     */
    data object ScreenResumed : DiscoveryHomeEvent

    /** «Открыть талон» на фокус-карточке. */
    data object TicketClicked : DiscoveryHomeEvent

    /** Акция (issue #104): куда она ведёт, решает ViewModel. */
    data class PromotionClicked(val promotionId: String) : DiscoveryHomeEvent
}

sealed interface DiscoveryHomeEffect : UiEffect {
    data class OpenPlace(val placeId: String) : DiscoveryHomeEffect

    /** `null` — поиск без предвыбранной категории. */
    data class OpenSearch(val category: PlaceCategory?) : DiscoveryHomeEffect

    data object OpenMap : DiscoveryHomeEffect

    /** Экран очереди заведения, где взят талон. */
    data class OpenTicket(val placeId: String, val placeName: String) : DiscoveryHomeEffect
}
