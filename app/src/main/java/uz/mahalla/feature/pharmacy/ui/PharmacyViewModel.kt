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
import uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft
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
 *
 * Владелец/менеджер заведения (issue #252) добавляет новый товар и правит
 * остаток существующего — `route.isOwner` приезжает уже выставленным из
 * «Моих заведений», где эта проверка (`places/my`) уже сделана: у товаров
 * аптеки нет своего `ownerId`, чтобы повторить её здесь.
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
        updateState { copy(placeName = route.placeName, isOwner = route.isOwner) }
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

            PharmacyEvent.AddProductClicked -> onAddProductClicked()
            PharmacyEvent.CreateFormDismissed -> updateState { copy(createForm = null) }
            is PharmacyEvent.CreateNameChanged -> updateCreateDraft { withName(event.value) }
            is PharmacyEvent.CreateManufacturerChanged ->
                updateCreateDraft { withManufacturer(event.value) }
            is PharmacyEvent.CreateDosageFormChanged ->
                updateCreateDraft { withDosageForm(event.value) }
            is PharmacyEvent.CreateStrengthChanged -> updateCreateDraft { withStrength(event.value) }
            is PharmacyEvent.CreateDescriptionChanged ->
                updateCreateDraft { withDescription(event.value) }
            is PharmacyEvent.CreatePriceChanged -> updateCreateDraft { withPrice(event.value) }
            is PharmacyEvent.CreateStockChanged -> updateCreateDraft { withStock(event.value) }
            is PharmacyEvent.CreatePrescriptionChanged ->
                updateCreateDraft { withPrescription(event.value) }
            PharmacyEvent.CreateSubmitted -> submitCreate()

            is PharmacyEvent.StockEditClicked -> onStockEditClicked(event.product)
            PharmacyEvent.StockFormDismissed -> updateState { copy(stockForm = null) }
            is PharmacyEvent.StockQuantityChanged -> updateState {
                copy(stockForm = stockForm?.copy(quantityText = event.value, failure = null))
            }
            PharmacyEvent.StockSubmitted -> submitStock()
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

    /** Кнопка скрыта не-владельцу самим экраном — проверка здесь на всякий случай. */
    private fun onAddProductClicked() {
        if (!currentState.isOwner) return
        updateState { copy(createForm = NewProductFormState()) }
    }

    private inline fun updateCreateDraft(
        crossinline transform: NewPharmacyProductDraft.() -> NewPharmacyProductDraft,
    ) {
        updateState {
            copy(
                createForm = createForm?.let {
                    it.copy(draft = it.draft.transform(), failure = null)
                },
            )
        }
    }

    /**
     * Успех перечитывает витрину целиком (тот же приём, что у правки карточки
     * места в issue #188), а не собирает карточку из черновика: сервер
     * возвращает `id`, и только он делает новый товар кликабельным в списке —
     * дважды разбирать ответ незачем, если список всё равно обновится.
     */
    private fun submitCreate() {
        val form = currentState.createForm ?: return
        if (form.submitting) return
        if (!form.draft.canSubmit) {
            updateState { copy(createForm = form.copy(submitAttempted = true)) }
            return
        }

        updateState { copy(createForm = form.copy(submitting = true, failure = null)) }
        viewModelScope.launch {
            when (val result = repository.createProduct(route.placeId, form.draft)) {
                is ApiResult.Failure -> updateState {
                    copy(createForm = createForm?.copy(submitting = false, failure = result.failure))
                }

                is ApiResult.Success -> {
                    updateState { copy(createForm = null) }
                    load(delayMillis = 0)
                }
            }
        }
    }

    /** Только владелец/менеджер и только среди уже показанных товаров. */
    private fun onStockEditClicked(product: PharmacyProduct) {
        if (!currentState.isOwner) return
        updateState {
            copy(
                stockForm = StockEditFormState(
                    productId = product.id,
                    productName = product.name,
                    quantityText = product.stockQuantity?.toString().orEmpty(),
                ),
            )
        }
    }

    /**
     * Успех правит список на месте: сервер уже подтвердил новый остаток, а
     * полная перезагрузка сбросила бы догруженный хвост и активный поиск.
     */
    private fun submitStock() {
        val form = currentState.stockForm ?: return
        if (form.submitting) return
        val quantity = form.parsedQuantity
        if (quantity == null) {
            updateState { copy(stockForm = form.copy(submitAttempted = true)) }
            return
        }

        updateState { copy(stockForm = form.copy(submitting = true, failure = null)) }
        viewModelScope.launch {
            when (val result = repository.updateStock(route.placeId, form.productId, quantity)) {
                is ApiResult.Failure -> updateState {
                    copy(stockForm = stockForm?.copy(submitting = false, failure = result.failure))
                }

                is ApiResult.Success -> updateState {
                    copy(
                        stockForm = null,
                        products = (products as? ScreenState.Content)?.let { content ->
                            ScreenState.Content(
                                content.data.map { item ->
                                    if (item.id == result.data.id) result.data else item
                                },
                            )
                        } ?: products,
                    )
                }
            }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
