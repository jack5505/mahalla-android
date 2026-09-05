package uz.mahalla.feature.pharmacy.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.pharmacy.data.PharmacyRepository
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
import uz.mahalla.navigation.PharmacyRoute
import javax.inject.Inject

/** У витрины нет переходов наружу: купить товар нечем (см. [PharmacyViewModel]). */
sealed interface PharmacyEffect : UiEffect

/**
 * Витрина аптеки (issue #100): что есть в наличии и почём.
 *
 * **Кнопки «купить» здесь нет и быть не должно**: своей ручки заказа
 * `pharmacy-controller` не отдаёт, то есть корзину аптеки бэкенду сейчас нечем
 * принять. Появится ручка — появится и вертикаль; до тех пор это витрина, а не
 * покупка.
 *
 * Поиск уходит **на сервер** с задержкой [SEARCH_DEBOUNCE_MS]: без неё каждая
 * буква — отдельный запрос. Фильтровать приехавший список нельзя — у ручки
 * есть пагинация (проверено живым запросом), и совпадение с третьей страницы
 * осталось бы невидимым.
 */
@HiltViewModel
class PharmacyViewModel @Inject constructor(
    private val repository: PharmacyRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<PharmacyState, PharmacyEvent, PharmacyEffect>(PharmacyState()) {

    private val route: PharmacyRoute = savedStateHandle.toRoute()

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        updateState { copy(placeName = route.placeName) }
        load(delayMillis = 0)
    }

    override fun onEvent(event: PharmacyEvent) {
        when (event) {
            is PharmacyEvent.QueryChanged -> {
                updateState { copy(query = event.query) }
                load(delayMillis = SEARCH_DEBOUNCE_MS)
            }

            PharmacyEvent.QuerySubmitted -> load(delayMillis = 0)
            PharmacyEvent.Retry -> load(delayMillis = 0)
            PharmacyEvent.Refreshed -> load(delayMillis = 0, refreshing = true)
            PharmacyEvent.LoadMore -> loadMore()
        }
    }

    /**
     * Новый запрос всегда отменяет предыдущий: иначе ответ на «aspiri» способен
     * прийти после ответа на «aspirin» и перезаписать более точный результат.
     *
     * Обновление жестом не показывает скелетон: список уже на экране, и
     * подменять его заглушкой поверх крутящегося индикатора незачем.
     */
    private fun load(delayMillis: Long, refreshing: Boolean = false) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        loadedPage = 0
        val query = currentState.query
        searchJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            updateState {
                copy(
                    products = if (refreshing) products else ScreenState.Loading,
                    isRefreshing = refreshing,
                    isLoadingMore = false,
                    loadMoreFailure = null,
                )
            }
            val result = repository.products(placeId = route.placeId, query = query, page = 0)
            updateState { copy(isRefreshing = false, searchedQuery = query) }
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
    }

    /**
     * Догрузка страницы. Провал не стирает уже показанные товары, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу больше
     * не сработает — поэтому хвост переходит в состояние «повторить» вместе с
     * причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     *
     * Запрос уходит с тем же [PharmacyState.searchedQuery], которому
     * соответствует показанный список, а не с тем, что человек уже успел
     * набрать: иначе к результатам одного поиска дописался бы хвост другого.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.products as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.products(
                placeId = route.placeId,
                query = state.searchedQuery,
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
                            products = ScreenState.Content(
                                appended(loaded.data, result.data.items),
                            ),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Товар может приехать на двух соседних страницах, если витрину правили
     * между запросами. В `LazyColumn` это дубликат ключа и падение, поэтому
     * дедупликация по id обязательна.
     */
    private fun appended(
        current: List<PharmacyProduct>,
        next: List<PharmacyProduct>,
    ): List<PharmacyProduct> {
        val known = current.mapTo(mutableSetOf(), PharmacyProduct::id)
        return current + next.filter { known.add(it.id) }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
