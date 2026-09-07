package uz.mahalla.feature.role.ui.places

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.MyPlacePage
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.testutil.FakeProviderRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * «Мои заведения» (issue #94): экран открывают, чтобы узнать судьбу заявки, —
 * поэтому проверяется в первую очередь, что статус доезжает до состояния и
 * перечитывается на возврате.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyPlacesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the application sent from the provider form is visible as pending`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Pending)))

        val state = MyPlacesViewModel(repository).state.value

        val place = (state.places as ScreenState.Content).data.single()
        assertEquals("p-1", place.id)
        assertEquals(PlaceModerationStatus.Pending, place.status)
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        val repository = FakeProviderRepository()

        val state = MyPlacesViewModel(repository).state.value

        assertTrue(state.places is ScreenState.Empty)
        assertFalse(state.hasMore)
    }

    @Test
    fun `a refusal is shown with the failure of the server`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = ApiResult.Failure(failure(ApiError.Unauthorized))

        val state = MyPlacesViewModel(repository).state.value

        assertEquals(ApiError.Unauthorized, (state.places as ScreenState.Error).failure.error)
    }

    @Test
    fun `coming back rereads the list because moderation decides without the app`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Pending)))
        val viewModel = MyPlacesViewModel(repository)

        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Active)))
        viewModel.onEvent(MyPlacesEvent.ScreenResumed)

        val place = (viewModel.state.value.places as ScreenState.Content).data.single()
        assertEquals(PlaceModerationStatus.Active, place.status)
        assertEquals(listOf(0, 0), repository.requestedPages)
    }

    @Test
    fun `the next page is appended and deduplicated`() = runTest {
        val repository = FakeProviderRepository()
        repository.pages[0] = page(listOf(place("p-1")), hasMore = true)
        // Заведение может приехать на двух страницах, если список изменился
        // между запросами: дубликат ключа роняет LazyColumn.
        repository.pages[1] = page(listOf(place("p-1"), place("p-2")), hasMore = false)
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(
            listOf("p-1", "p-2"),
            (state.places as ScreenState.Content).data.map(MyPlace::id),
        )
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `a failed load more keeps the list and offers a retry`() = runTest {
        val repository = FakeProviderRepository()
        repository.pages[0] = page(listOf(place("p-1")), hasMore = true)
        repository.pages[1] = ApiResult.Failure(failure(ApiError.NoConnection))
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(1, (state.places as ScreenState.Content).data.size)
        assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `an active place opens its card in the catalog`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Active)))
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.PlaceClicked("p-1"))

        assertEquals(MyPlacesEffect.OpenPlace("p-1"), viewModel.effects.first())
    }

    @Test
    fun `an application under moderation leads nowhere`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Pending)))
        val viewModel = MyPlacesViewModel(repository)
        val effects = mutableListOf<MyPlacesEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        // `GET places/{id}` ответил бы «заведение не найдено» — а нажатие в
        // ошибку хуже строки, которая не нажимается.
        viewModel.onEvent(MyPlacesEvent.PlaceClicked("p-1"))
        // Заведение, которого в списке нет вовсе, тоже никуда не ведёт.
        viewModel.onEvent(MyPlacesEvent.PlaceClicked("p-404"))

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `empty list leads to the provider form`() = runTest {
        val viewModel = MyPlacesViewModel(FakeProviderRepository())

        viewModel.onEvent(MyPlacesEvent.RegisterPlaceRequested)

        assertEquals(MyPlacesEffect.OpenProviderForm, viewModel.effects.first())
    }

    @Test
    fun `availability is switched in place with the state the server confirmed`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(
            listOf(place("p-1", PlaceModerationStatus.Active).copy(isAvailable = true)),
        )
        repository.toggleResult = ApiResult.Success(false)
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.AvailabilityToggled("p-1"))

        // Известное состояние уходит на сервер: ручка — переключатель, и без
        // него нечем понять исход, когда сервер промолчит о новом значении.
        assertEquals(listOf("p-1" to true), repository.toggled)
        val place = (viewModel.state.value.places as ScreenState.Content).data.single()
        assertFalse(place.isAvailable)
        // Список правится на месте: перезагрузка сбросила бы догруженный хвост.
        assertEquals(listOf(0), repository.requestedPages)
        assertNull(viewModel.state.value.pendingPlaceId)
    }

    @Test
    fun `a refused toggle keeps the flag and explains why`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(
            listOf(place("p-1", PlaceModerationStatus.Active).copy(isAvailable = true)),
        )
        repository.toggleResult = ApiResult.Failure(failure(ApiError.Forbidden))
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.AvailabilityToggled("p-1"))

        val state = viewModel.state.value
        assertTrue((state.places as ScreenState.Content).data.single().isAvailable)
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertNull(state.pendingPlaceId)
    }

    @Test
    fun `a staff member cannot switch availability`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(
            listOf(
                place("p-1", PlaceModerationStatus.Active).copy(staffRole = PlaceStaffRole.Staff),
            ),
        )
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.AvailabilityToggled("p-1"))

        // Экран такого переключателя и не рисует; в сеть он тоже не уходит.
        assertTrue(repository.toggled.isEmpty())
    }

    @Test
    fun `an application under moderation has no availability switch either`() = runTest {
        val repository = FakeProviderRepository()
        repository.defaultPage = page(listOf(place("p-1", PlaceModerationStatus.Pending)))
        val viewModel = MyPlacesViewModel(repository)

        viewModel.onEvent(MyPlacesEvent.AvailabilityToggled("p-1"))

        assertTrue(repository.toggled.isEmpty())
    }

    private fun page(
        items: List<MyPlace>,
        hasMore: Boolean = false,
    ): ApiResult<MyPlacePage> = ApiResult.Success(MyPlacePage(items = items, hasMore = hasMore))

    private fun place(
        id: String,
        status: PlaceModerationStatus = PlaceModerationStatus.Active,
    ) = MyPlace(
        id = id,
        name = "Osh Markazi",
        category = PlaceCategory.Food,
        status = status,
        staffRole = PlaceStaffRole.Owner,
    )

    private fun failure(error: ApiError) = ApiFailure(error)
}
