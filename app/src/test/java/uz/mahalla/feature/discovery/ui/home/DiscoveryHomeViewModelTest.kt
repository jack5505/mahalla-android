package uz.mahalla.feature.discovery.ui.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.promotions.domain.Promotion
import uz.mahalla.feature.promotions.domain.PromotionFeed
import uz.mahalla.feature.promotions.domain.PromotionPage
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakePromotionsRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place
import uz.mahalla.testutil.promotion
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Главная (эпик 4.1): состояния, блоки и переходы. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryHomeViewModelTest {

    // Здесь таймеров нет — загрузка должна выполниться на месте, без
    // advanceUntilIdle() после каждого события.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeCatalogRepository()

    private val promotions = FakePromotionsRepository()

    @Test
    fun `successful load splits the answer into sections`() = runTest {
        repository.respondWith(
            listOf(
                place("near", distanceMeters = 100, rating = 4.0, reviewCount = 5),
                place("top", distanceMeters = 900, rating = 4.9, reviewCount = 200),
            ),
        )

        val state = viewModel().state.value

        val content = (state.content as ScreenState.Content).data
        assertEquals(listOf("near", "top"), content.nearby.map(Place::id))
        assertEquals(listOf("top"), content.recommended.map(Place::id))
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `home asks the catalog without filters`() = runTest {
        // Главная — витрина всего, что рядом; фильтры живут на экране поиска.
        repository.respondWith(listOf(place("p")))

        viewModel()

        val (filters, page) = repository.requestedFilters.single()
        assertTrue(filters.isDefault)
        assertEquals(0, page)
    }

    @Test
    fun `empty answer is an empty state, not empty content`() = runTest {
        repository.respondWith(emptyList())

        assertEquals(ScreenState.Empty, viewModel().state.value.content)
    }

    @Test
    fun `network error becomes an error state`() = runTest {
        repository.failWith(ApiError.NoConnection)

        assertEquals(
            ScreenState.Error(ApiError.NoConnection),
            viewModel().state.value.content,
        )
    }

    @Test
    fun `retry reloads after a failure`() = runTest {
        repository.failWith(ApiError.Timeout)
        val viewModel = viewModel()
        repository.respondWith(listOf(place("p")))

        viewModel.onEvent(DiscoveryHomeEvent.Retry)

        assertTrue(viewModel.state.value.content is ScreenState.Content)
    }

    @Test
    fun `refresh keeps the list on screen instead of showing a skeleton`() = runTest {
        // Обновление поверх готовых данных — не повторная загрузка экрана.
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.Refresh)

        assertTrue(viewModel.state.value.content is ScreenState.Content)
        assertFalse("флаг обновления снимается по завершении", viewModel.state.value.isRefreshing)
    }

    @Test
    fun `cached answer is marked for the screen`() = runTest {
        repository.respondWith(listOf(place("p")), fromCache = true)

        val content = (viewModel().state.value.content as ScreenState.Content).data

        assertTrue(content.fromCache)
    }

    @Test
    fun `category tap opens search with that category`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.CategoryClicked(PlaceCategory.Pharmacy))

        assertEquals(
            DiscoveryHomeEffect.OpenSearch(PlaceCategory.Pharmacy),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `search bar opens search without a category`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.SearchClicked)

        assertEquals(DiscoveryHomeEffect.OpenSearch(null), viewModel.effects.first())
    }

    @Test
    fun `place tap opens the card`() = runTest {
        repository.respondWith(listOf(place("p-1")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.PlaceClicked("p-1"))

        assertEquals(DiscoveryHomeEffect.OpenPlace("p-1"), viewModel.effects.first())
    }

    @Test
    fun `map button opens the map`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.MapClicked)

        assertEquals(DiscoveryHomeEffect.OpenMap, viewModel.effects.first())
    }

    @Test
    fun `all six categories are offered`() = runTest {
        repository.respondWith(listOf(place("p")))

        assertEquals(PlaceCategory.selectable, viewModel().state.value.categories)
    }

    // --- Акции платформы (issue #104) ---

    @Test
    fun `platform promotions fill their own section`() = runTest {
        repository.respondWith(listOf(place("p")))
        promotions.platform = ApiResult.Success(
            PromotionPage(items = listOf(promotion("promo-1"), promotion("promo-2"))),
        )

        val state = viewModel().state.value

        assertEquals(listOf("promo-1", "promo-2"), state.promotions.map(Promotion::id))
        // Просим с запасом: часть первой страницы может оказаться просроченной.
        assertEquals(listOf(PromotionFeed.HOME_PAGE_SIZE), promotions.requestedSizes)
    }

    @Test
    fun `an empty catalog does not take the promotions off the screen`() = runTest {
        // Ровно текущее состояние стенда: мест нет, акции есть (issue #53).
        repository.respondWith(emptyList())
        promotions.platform = ApiResult.Success(PromotionPage(items = listOf(promotion("promo-1"))))

        val state = viewModel().state.value

        assertEquals(ScreenState.Empty, state.content)
        assertEquals(listOf("promo-1"), state.promotions.map(Promotion::id))
    }

    @Test
    fun `a catalog failure does not take the promotions off the screen either`() = runTest {
        repository.failWith(ApiError.NoConnection)
        promotions.platform = ApiResult.Success(PromotionPage(items = listOf(promotion("promo-1"))))

        val state = viewModel().state.value

        assertTrue(state.content is ScreenState.Error)
        assertEquals(listOf("promo-1"), state.promotions.map(Promotion::id))
    }

    @Test
    fun `a promotions failure hides the section instead of breaking the home`() = runTest {
        repository.respondWith(listOf(place("p")))
        promotions.platform = ApiResult.Failure(ApiError.NoConnection)

        val state = viewModel().state.value

        assertTrue("каталог приехал — экран ошибки был бы хуже пустого блока", state.content is ScreenState.Content)
        assertTrue(state.promotions.isEmpty())
    }

    @Test
    fun `a finished promotion is not shown`() = runTest {
        repository.respondWith(listOf(place("p")))
        promotions.platform = ApiResult.Success(
            PromotionPage(
                items = listOf(
                    promotion("gone", endsAt = NOW.minusSeconds(1)),
                    promotion("live"),
                ),
            ),
        )

        assertEquals(listOf("live"), viewModel().state.value.promotions.map(Promotion::id))
    }

    @Test
    fun `a promotion of a place opens its card`() = runTest {
        repository.respondWith(listOf(place("p")))
        promotions.platform = ApiResult.Success(
            PromotionPage(items = listOf(promotion("promo-1", placeId = "p-7"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.PromotionClicked("promo-1"))

        assertEquals(DiscoveryHomeEffect.OpenPlace("p-7"), viewModel.effects.first())
    }

    @Test
    fun `a platform promotion leads nowhere`() = runTest {
        repository.respondWith(listOf(place("p")))
        promotions.platform = ApiResult.Success(
            PromotionPage(items = listOf(promotion("promo-1"), promotion("promo-2", placeId = "p-7"))),
        )
        val viewModel = viewModel()

        // Акция без заведения нажатия не принимает — вести по ней некуда.
        viewModel.onEvent(DiscoveryHomeEvent.PromotionClicked("promo-1"))
        viewModel.onEvent(DiscoveryHomeEvent.PromotionClicked("promo-2"))

        assertEquals(DiscoveryHomeEffect.OpenPlace("p-7"), viewModel.effects.first())
    }

    @Test
    fun `refresh reloads the promotions too`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        promotions.platform = ApiResult.Success(PromotionPage(items = listOf(promotion("fresh"))))

        viewModel.onEvent(DiscoveryHomeEvent.Refresh)

        assertEquals(listOf("fresh"), viewModel.state.value.promotions.map(Promotion::id))
    }

    private fun viewModel() = DiscoveryHomeViewModel(
        repository = repository,
        promotions = promotions,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
    }
}
