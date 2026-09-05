package uz.mahalla.feature.discovery.ui.home

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.PlacePage
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.HomeSections
import uz.mahalla.feature.promotions.data.PromotionsRepository
import uz.mahalla.feature.promotions.domain.PromotionFeed
import uz.mahalla.feature.promotions.domain.PromotionPage
import uz.mahalla.feature.promotions.domain.PromotionTarget
import java.time.Clock
import javax.inject.Inject

/**
 * Главная (эпик 4.1).
 *
 * Один запрос каталога без фильтров, из которого собираются оба блока: «рядом»
 * и «рекомендации» — это разные срезы одной выдачи, и второй сетевой вызов
 * ради них означал бы вдвое больше трафика при том же содержимом.
 *
 * Акции (issue #104) — отдельная ручка и отдельное поле состояния, и просятся
 * они **параллельно** каталогу: последовательный запрос удвоил бы время до
 * первого экрана. Каталог и акции друг друга не роняют — пустая выдача не
 * повод спрятать акции, а отказ акций не повод потерять выдачу.
 */
@HiltViewModel
class DiscoveryHomeViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val promotions: PromotionsRepository,
    private val clock: Clock,
) : MviViewModel<DiscoveryHomeState, DiscoveryHomeEvent, DiscoveryHomeEffect>(
    DiscoveryHomeState(),
) {

    /** Загрузка ровно одна: повторный retry не должен плодить гонку ответов. */
    private var loadJob: Job? = null

    init {
        load(refreshing = false)
    }

    override fun onEvent(event: DiscoveryHomeEvent) {
        when (event) {
            DiscoveryHomeEvent.Retry -> load(refreshing = false)
            DiscoveryHomeEvent.Refresh -> load(refreshing = true)
            is DiscoveryHomeEvent.CategoryClicked ->
                emitEffect(DiscoveryHomeEffect.OpenSearch(event.category))

            is DiscoveryHomeEvent.PlaceClicked ->
                emitEffect(DiscoveryHomeEffect.OpenPlace(event.placeId))

            DiscoveryHomeEvent.SearchClicked ->
                emitEffect(DiscoveryHomeEffect.OpenSearch(category = null))

            DiscoveryHomeEvent.MapClicked -> emitEffect(DiscoveryHomeEffect.OpenMap)

            is DiscoveryHomeEvent.PromotionClicked -> openPromotion(event.promotionId)
        }
    }

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        updateState {
            copy(
                isRefreshing = refreshing,
                // Скелетон показываем только когда показывать больше нечего.
                content = if (refreshing && content is ScreenState.Content) content else ScreenState.Loading,
            )
        }
        loadJob = viewModelScope.launch {
            val places = async { repository.places(DiscoveryFilters()) }
            val promos = async { promotions.platformPromotions() }
            val result = places.await()
            updateState { copy(isRefreshing = false, content = result.toContent()) }
            applyPromotions(promos.await())
        }
    }

    /**
     * Отказ акций прячет секцию, а не роняет главную: ради дополнительного
     * блока человек сюда не приходил, а экран ошибки поверх приехавшего
     * каталога был бы хуже отсутствующего блока. Причина отказа при этом
     * теряется — но она относится к тому, чего на экране всё равно нет.
     */
    private fun applyPromotions(result: ApiResult<PromotionPage>) {
        val items = when (result) {
            is ApiResult.Failure -> emptyList()
            // Истёкшую акцию не показываем: обещание скидки, которой уже нет,
            // хуже пустого блока.
            is ApiResult.Success -> PromotionFeed.home(result.data.items, clock.instant())
        }
        updateState { copy(promotions = items) }
    }

    /**
     * Переход по акции. Экрана самой акции в приложении нет, поэтому ведём
     * только туда, чем её цель заведомо является, — на карточку заведения
     * (issue #104). Акция платформы без заведения не ведёт никуда и на экране
     * нажатия не принимает.
     */
    private fun openPromotion(promotionId: String) {
        val promotion = currentState.promotions.firstOrNull { it.id == promotionId } ?: return
        when (val target = PromotionTarget.of(promotion)) {
            is PromotionTarget.Place -> emitEffect(DiscoveryHomeEffect.OpenPlace(target.placeId))
            PromotionTarget.None -> Unit
        }
    }

    private fun ApiResult<PlacePage>.toContent(): ScreenState<DiscoveryHomeContent> = when (this) {
        is ApiResult.Failure -> ScreenState.Error(failure)
        is ApiResult.Success -> if (data.items.isEmpty()) {
            ScreenState.Empty
        } else {
            ScreenState.Content(
                DiscoveryHomeContent(
                    nearby = HomeSections.nearby(data.items),
                    recommended = HomeSections.recommended(data.items),
                    fromCache = data.fromCache,
                ),
            )
        }
    }
}
