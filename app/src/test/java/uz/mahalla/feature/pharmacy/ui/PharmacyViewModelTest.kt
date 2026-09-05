package uz.mahalla.feature.pharmacy.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
import uz.mahalla.feature.pharmacy.domain.PharmacyProductPage
import uz.mahalla.feature.pharmacy.domain.ProductStock
import uz.mahalla.testutil.FakePharmacyRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Витрина аптеки (issue #100): список, поиск на сервере, догрузка.
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PharmacyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakePharmacyRepository()

    @Test
    fun `the showcase is loaded for the place from the route`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1"))),
            )

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertEquals("Dori-Darmon", state.placeName)
            assertEquals(listOf("p-1"), state.products.items().map { it.id })
            // Первая загрузка идёт без задержки: экран открыли — значит ждут.
            assertEquals(
                listOf(FakePharmacyRepository.Request("p-1-place", "", 0)),
                repository.requests,
            )
        }

    @Test
    fun `an empty showcase is an empty state, not an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Ровно то, что сейчас отвечает стенд: каталог пуст (issue #53).
            val viewModel = viewModel()
            runCurrent()

            assertEquals(ScreenState.Empty, viewModel.state.value.products)
        }

    @Test
    fun `the search query goes to the server, and only after a pause`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            repository.requests.clear()

            viewModel.onEvent(PharmacyEvent.QueryChanged("as"))
            viewModel.onEvent(PharmacyEvent.QueryChanged("asp"))
            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin"))
            runCurrent()

            // Каждая буква отдельным запросом — это три ненужных похода в сеть.
            assertTrue(repository.requests.isEmpty())

            advanceTimeBy(PharmacyViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            assertEquals(
                listOf(FakePharmacyRepository.Request("p-1-place", "aspirin", 0)),
                repository.requests,
            )
            assertEquals("aspirin", viewModel.state.value.searchedQuery)
        }

    @Test
    fun `pressing search on the keyboard does not wait for the pause`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            repository.requests.clear()

            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin"))
            viewModel.onEvent(PharmacyEvent.QuerySubmitted)
            runCurrent()

            assertEquals(
                listOf(FakePharmacyRepository.Request("p-1-place", "aspirin", 0)),
                repository.requests,
            )
        }

    @Test
    fun `nothing found by a query is not the same as an empty showcase`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1"))),
            )
            repository.pages["aspirin" to 0] = ApiResult.Success(PharmacyProductPage())

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(PharmacyEvent.QuerySubmitted)
            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin"))
            advanceTimeBy(PharmacyViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            // Экран различает их только по `searchedQuery`: сообщение «в этой
            // аптеке пока нет товаров» на месте «ничего не нашлось» читалось
            // бы как поломка поиска.
            assertEquals(ScreenState.Empty, viewModel.state.value.products)
            assertEquals("aspirin", viewModel.state.value.searchedQuery)
        }

    @Test
    fun `a late answer to a shorter query does not overwrite a more precise one`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages["asp" to 0] = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("stale"))),
            )
            repository.pages["aspirin" to 0] = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("fresh"))),
            )

            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(PharmacyEvent.QueryChanged("asp"))
            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin"))
            advanceTimeBy(PharmacyViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            assertEquals(listOf("fresh"), viewModel.state.value.products.items().map { it.id })
            assertFalse(repository.requests.any { it.query == "asp" })
        }

    @Test
    fun `the next page is appended, and a duplicate does not break the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages["" to 0] = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1")), hasMore = true),
            )
            repository.pages["" to 1] = ApiResult.Success(
                // Витрину правили между запросами — товар приехал дважды.
                PharmacyProductPage(items = listOf(product("p-1"), product("p-2"))),
            )

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(PharmacyEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(listOf("p-1", "p-2"), state.products.items().map { it.id })
            assertFalse(state.hasMore)
            assertFalse(state.isLoadingMore)
        }

    @Test
    fun `the tail is loaded for the query the list belongs to`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages["aspirin" to 0] = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1")), hasMore = true),
            )

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin"))
            advanceTimeBy(PharmacyViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            // Человек уже набирает следующее слово, а хвост относится к
            // показанному списку — иначе к результатам одного поиска
            // дописались бы результаты другого.
            viewModel.onEvent(PharmacyEvent.QueryChanged("aspirin c"))
            viewModel.onEvent(PharmacyEvent.LoadMore)
            runCurrent()

            assertEquals(
                FakePharmacyRepository.Request("p-1-place", "aspirin", 1),
                repository.requests.last(),
            )
        }

    @Test
    fun `a failed tail keeps the list and offers a retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages["" to 0] = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1")), hasMore = true),
            )
            repository.pages["" to 1] = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(PharmacyEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(listOf("p-1"), state.products.items().map { it.id })
            assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
            assertFalse(state.isLoadingMore)
        }

    @Test
    fun `a failure of the whole showcase carries the server text`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Failure(ApiError.Forbidden)

            val viewModel = viewModel()
            runCurrent()

            val products = viewModel.state.value.products
            assertTrue(products is ScreenState.Error)
            assertEquals(ApiError.Forbidden, (products as ScreenState.Error).failure.error)
            assertFalse(viewModel.state.value.hasMore)
        }

    @Test
    fun `retry asks again, refresh keeps the list on the screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Success(
                PharmacyProductPage(items = listOf(product("p-1"))),
            )

            val viewModel = viewModel()
            runCurrent()

            // Запрос придерживается, чтобы увидеть состояние экрана **во
            // время** обновления, а не только его исход.
            val gate = CompletableDeferred<Unit>()
            repository.gate = gate

            viewModel.onEvent(PharmacyEvent.Refreshed)
            runCurrent()

            // Обновление жестом не подменяет список скелетоном: он уже на
            // экране, а индикатор и так крутится сам.
            assertTrue(viewModel.state.value.products is ScreenState.Content)
            assertTrue(viewModel.state.value.isRefreshing)

            gate.complete(Unit)
            runCurrent()
            assertFalse(viewModel.state.value.isRefreshing)
            repository.gate = null

            viewModel.onEvent(PharmacyEvent.Retry)
            runCurrent()

            assertEquals(3, repository.requests.size)
        }

    @Test
    fun `there is no tail to load when the server says there is none`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            repository.requests.clear()

            viewModel.onEvent(PharmacyEvent.LoadMore)
            runCurrent()

            assertTrue(repository.requests.isEmpty())
            assertNull(viewModel.state.value.loadMoreFailure)
        }

    private fun ScreenState<List<PharmacyProduct>>.items(): List<PharmacyProduct> =
        (this as? ScreenState.Content)?.data.orEmpty()

    private fun product(id: String) = PharmacyProduct(
        id = id,
        name = "Paratsetamol",
        priceSum = 12_000,
        stock = ProductStock.InStock,
    )

    private fun viewModel() = PharmacyViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to "p-1-place", "placeName" to "Dori-Darmon"),
        ),
    )
}
