package uz.mahalla.feature.freelancer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerPage
import uz.mahalla.feature.freelancer.ui.catalog.FreelancersEffect
import uz.mahalla.feature.freelancer.ui.catalog.FreelancersEvent
import uz.mahalla.feature.freelancer.ui.catalog.FreelancersViewModel
import uz.mahalla.testutil.FakeFreelancerRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Каталог мастеров (issue #107): фильтр, догрузка, переход в профиль. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreelancersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerRepository()

    @Test
    fun `catalog loads the first page without a filter`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultCatalogPage = ApiResult.Success(page(listOf(freelancer("f-1"))))

            val viewModel = FreelancersViewModel(repository)
            runCurrent()

            // Пустой фильтр доезжает до репозитория как есть — отбрасывает
            // его он сам, чтобы `profession=` не уехало в запрос
            // (`FreelancerRepositoryTest`).
            assertEquals(listOf(0 to ""), repository.catalogRequests)
            val content = viewModel.state.value.freelancers as ScreenState.Content
            assertEquals(listOf("f-1"), content.data.map { it.id })
        }

    /** Пустая выдача — ответ сервера (каталог стенда пуст), а не поломка. */
    @Test
    fun `empty catalog is an empty state and not an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = FreelancersViewModel(repository)
            runCurrent()

            assertEquals(ScreenState.Empty, viewModel.state.value.freelancers)
        }

    /**
     * Без задержки каждая буква становится отдельным сетевым вызовом, ответы
     * приходят вразнобой и список моргает.
     */
    @Test
    fun `profession filter is debounced`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = FreelancersViewModel(repository)
        runCurrent()
        repository.catalogRequests.clear()

        viewModel.onEvent(FreelancersEvent.ProfessionChanged("s"))
        viewModel.onEvent(FreelancersEvent.ProfessionChanged("sa"))
        viewModel.onEvent(FreelancersEvent.ProfessionChanged("san"))
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(0 to "san"), repository.catalogRequests)
    }

    /** Enter — осознанное действие: ждать его незачем. */
    @Test
    fun `explicit submit searches immediately`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = FreelancersViewModel(repository)
        runCurrent()
        viewModel.onEvent(FreelancersEvent.ProfessionChanged("santexnik"))
        repository.catalogRequests.clear()

        viewModel.onEvent(FreelancersEvent.ProfessionSubmitted)
        runCurrent()

        assertEquals(listOf(0 to "santexnik"), repository.catalogRequests)
    }

    @Test
    fun `load more appends the next page and keeps the filter`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.catalogPages[0] = ApiResult.Success(
                page(listOf(freelancer("f-1")), hasMore = true),
            )
            repository.catalogPages[1] = ApiResult.Success(page(listOf(freelancer("f-2"))))
            val viewModel = FreelancersViewModel(repository)
            runCurrent()
            viewModel.onEvent(FreelancersEvent.ProfessionSubmitted)
            runCurrent()

            viewModel.onEvent(FreelancersEvent.LoadMore)
            runCurrent()

            val content = viewModel.state.value.freelancers as ScreenState.Content
            assertEquals(listOf("f-1", "f-2"), content.data.map { it.id })
            assertFalse(viewModel.state.value.hasMore)
            assertEquals(1, repository.catalogRequests.last().first)
        }

    /**
     * Мастер может приехать на двух соседних страницах, если каталог
     * изменился между запросами: в `LazyColumn` дубликат ключа роняет экран.
     */
    @Test
    fun `duplicates across pages are dropped`() = runTest(mainDispatcherRule.dispatcher) {
        repository.catalogPages[0] = ApiResult.Success(
            page(listOf(freelancer("f-1")), hasMore = true),
        )
        repository.catalogPages[1] = ApiResult.Success(
            page(listOf(freelancer("f-1"), freelancer("f-2"))),
        )
        val viewModel = FreelancersViewModel(repository)
        runCurrent()

        viewModel.onEvent(FreelancersEvent.LoadMore)
        runCurrent()

        val content = viewModel.state.value.freelancers as ScreenState.Content
        assertEquals(listOf("f-1", "f-2"), content.data.map { it.id })
    }

    /** Провал догрузки не стирает список, но и не крутит спиннер вечно. */
    @Test
    fun `failed load more keeps the list and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.catalogPages[0] = ApiResult.Success(
                page(listOf(freelancer("f-1")), hasMore = true),
            )
            repository.catalogPages[1] = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = FreelancersViewModel(repository)
            runCurrent()

            viewModel.onEvent(FreelancersEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.freelancers is ScreenState.Content)
            assertFalse(state.isLoadingMore)
            assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        }

    @Test
    fun `failed first page becomes an error state`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultCatalogPage = ApiResult.Failure(ApiError.NoConnection)

        val viewModel = FreelancersViewModel(repository)
        runCurrent()

        val freelancers = viewModel.state.value.freelancers
        assertEquals(ApiError.NoConnection, (freelancers as ScreenState.Error).error)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `tapping a freelancer opens the profile with the name from the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultCatalogPage = ApiResult.Success(
                page(listOf(freelancer("f-1", name = "Aziz"))),
            )
            val viewModel = FreelancersViewModel(repository)
            runCurrent()

            val effects = mutableListOf<FreelancersEffect>()
            val job = launch { effects += viewModel.effects.first() }
            viewModel.onEvent(FreelancersEvent.FreelancerClicked("f-1"))
            runCurrent()
            job.join()

            assertEquals(FreelancersEffect.OpenFreelancer("f-1", "Aziz"), effects.single())
        }

    /** Тап по мастеру не из текущего списка никуда не ведёт. */
    @Test
    fun `unknown freelancer is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = FreelancersViewModel(repository)
        runCurrent()

        val effects = mutableListOf<FreelancersEffect>()
        val job = launch { viewModel.effects.collect { effects += it } }
        viewModel.onEvent(FreelancersEvent.FreelancerClicked("f-404"))
        runCurrent()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `pull to refresh reloads the first page without a skeleton`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultCatalogPage = ApiResult.Success(page(listOf(freelancer("f-1"))))
            val viewModel = FreelancersViewModel(repository)
            runCurrent()

            viewModel.onEvent(FreelancersEvent.Refreshed)
            runCurrent()

            assertFalse(viewModel.state.value.isRefreshing)
            assertNull(viewModel.state.value.loadMoreFailure)
            assertEquals(listOf(0, 0), repository.catalogRequests.map { it.first })
        }

    private fun page(items: List<Freelancer>, hasMore: Boolean = false) =
        FreelancerPage(items = items, hasMore = hasMore)

    private fun freelancer(id: String, name: String = "Usta $id") =
        Freelancer(id = id, name = name)

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
