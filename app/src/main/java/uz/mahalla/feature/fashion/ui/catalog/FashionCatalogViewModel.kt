package uz.mahalla.feature.fashion.ui.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionRepository
import uz.mahalla.feature.fashion.domain.FashionCatalogPage
import uz.mahalla.feature.fashion.domain.FashionProduct
import uz.mahalla.navigation.FashionArgs
import javax.inject.Inject

/**
 * Витрина магазина одежды (issue #108): категории и товары страницами.
 *
 * Каталог анонимен (`200` без токена), а корзина требует входа — поэтому её
 * счётчик берётся отдельным запросом, и его отказ на выдачу не влияет: до
 * входа магазин всё равно можно смотреть.
 *
 * Аргументы читаются из `SavedStateHandle` по имени, а не через `toRoute()`:
 * тот разбирает маршрут настоящим `Bundle`, которого в JVM-тестах нет.
 */
@HiltViewModel
class FashionCatalogViewModel @Inject constructor(
    private val repository: FashionRepository,
    private val cartRepository: FashionCartRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<FashionCatalogState, FashionCatalogEvent, FashionCatalogEffect>(
    FashionCatalogState(),
) {

    private val storeId: String = savedStateHandle[FashionArgs.PLACE_ID] ?: ""
    private val placeName: String = savedStateHandle[FashionArgs.PLACE_NAME] ?: ""

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        val name = placeName
        updateState { copy(placeName = name) }
        loadCategories()
        load()
        refreshCartCount()
    }

    override fun onEvent(event: FashionCatalogEvent) {
        when (event) {
            // Товары не перезапрашиваются: витрина меняется медленнее, чем
            // человек ходит в карточку товара и обратно, а вот корзина
            // меняется именно там.
            FashionCatalogEvent.ScreenResumed -> refreshCartCount()

            FashionCatalogEvent.Refreshed -> load(showLoading = false, refreshing = true)
            FashionCatalogEvent.Retry -> load()
            FashionCatalogEvent.CategoriesRetry -> loadCategories()
            FashionCatalogEvent.LoadMore -> loadMore()

            is FashionCatalogEvent.CategorySelected -> selectCategory(event.categoryId)

            is FashionCatalogEvent.ProductClicked ->
                emitEffect(FashionCatalogEffect.OpenProduct(event.productId))

            FashionCatalogEvent.CartClicked -> emitEffect(FashionCatalogEffect.OpenCart)
        }
    }

    /**
     * Смена категории. Повторный тап по выбранной снимает фильтр: отдельной
     * кнопки «сбросить» на узкой полосе чипов нет, а вернуться ко всей витрине
     * человек хочет чаще, чем выбрать ту же категорию второй раз.
     */
    private fun selectCategory(categoryId: String?) {
        val next = categoryId?.takeIf { it != currentState.selectedCategoryId }
        if (next == currentState.selectedCategoryId) return
        updateState { copy(selectedCategoryId = next) }
        load()
    }

    private fun loadCategories() {
        updateState { copy(categories = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.categories()
            updateState {
                copy(
                    categories = when (result) {
                        is ApiResult.Failure -> ScreenState.Error(result.failure)
                        // Пустой справочник — не ошибка: фильтровать нечем, и
                        // полоса чипов просто не рисуется.
                        is ApiResult.Success -> if (result.data.isEmpty()) {
                            ScreenState.Empty
                        } else {
                            ScreenState.Content(result.data)
                        }
                    },
                )
            }
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                products = if (showLoading) ScreenState.Loading else products,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.catalog(
                storeId = storeId,
                categoryId = currentState.selectedCategoryId,
                page = 0,
            )
            applyFirstPage(result)
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyFirstPage(result: ApiResult<FashionCatalogPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(products = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    products = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Догрузка. Провал не стирает показанные товары, но и дёргать сеть в цикле
     * нельзя: список не вырос, автотриггер по концу больше не сработает —
     * поэтому хвост переходит в состояние «повторить» вместе с причиной.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.products as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.catalog(
                storeId = storeId,
                categoryId = currentState.selectedCategoryId,
                page = nextPage,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            products = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Товар может приехать на двух соседних страницах, если витрина
     * изменилась между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(
        current: List<FashionProduct>,
        next: List<FashionProduct>,
    ): List<FashionProduct> {
        val known = current.mapTo(mutableSetOf(), FashionProduct::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Счётчик корзины. Отказ (в том числе `401` до входа) бейдж не трогает:
     * обнулить его из-за пропавшей сети значило бы соврать, что корзина
     * пуста — то же правило, что у счётчика уведомлений (issue #81).
     */
    private fun refreshCartCount() {
        viewModelScope.launch {
            val result = cartRepository.cart()
            if (result is ApiResult.Success) {
                updateState { copy(cartCount = result.data.itemCount) }
            }
        }
    }
}
