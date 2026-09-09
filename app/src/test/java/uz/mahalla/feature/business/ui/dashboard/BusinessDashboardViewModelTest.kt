package uz.mahalla.feature.business.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessSection
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Бизнес-панель (задача 12.1) и ролевой доступ.
 *
 * Проверяется порядок: **сначала права, потом всё остальное**. Аналитику
 * чужого заведения спрашивать незачем — а показать «нет доступа» вместо 403 от
 * ручки метрик и есть разделение витрины клиента и панели бизнеса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessDashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the access is asked first and the metrics follow`() = runTest {
        val repository = FakeBusinessRepository()
        repository.dashboardResult = ApiResult.Success(
            BusinessDashboard.from(mapOf("orders" to 42L, "revenue" to 4_850_000L)),
        )

        val state = viewModel(repository).state.value

        assertEquals(listOf(PLACE), repository.accessRequests)
        assertEquals(listOf(PLACE), repository.dashboardRequests)
        assertEquals(2, (state.metrics as ScreenState.Content).data.metrics.size)
    }

    /**
     * Заведения нет среди «моих» — метрики не спрашиваются вовсе. Это и есть
     * разделение витрины и панели: клиентская проверка экономит запрос,
     * который сервер всё равно отверг бы.
     *
     * Состояние при этом [ScreenState.Empty], а не `Error`: «это не ваше
     * заведение» — ответ по существу, и общий «технический сбой» с кнопкой
     * «повторить», которая гарантированно перелистает `places/my` и снова
     * откажет, тут был бы прямой ложью (нашло ревью).
     */
    @Test
    fun `without access the metrics are not requested and the state is empty`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Failure(
            ApiFailure(ApiError.Business(BusinessAccess.NO_ACCESS_CODE)),
        )

        val state = viewModel(repository).state.value

        assertTrue(repository.dashboardRequests.isEmpty())
        assertTrue(state.access is ScreenState.Empty)
        // Метрики тоже пусты, а не в вечной загрузке под объяснением.
        assertTrue(state.metrics is ScreenState.Empty)
    }

    /** Настоящий сбой остаётся ошибкой с повтором — его-то повторять и надо. */
    @Test
    fun `a network failure stays an error with a retry`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Failure(ApiFailure(ApiError.NoConnection))

        val state = viewModel(repository).state.value

        assertTrue(repository.dashboardRequests.isEmpty())
        assertEquals(
            ApiError.NoConnection,
            (state.access as ScreenState.Error).failure.error,
        )
    }

    /** Отказ аналитики не прячет разделы: они грузятся другой ручкой. */
    @Test
    fun `a failed dashboard keeps the access and the sections`() = runTest {
        val repository = FakeBusinessRepository()
        repository.dashboardResult = ApiResult.Failure(ApiFailure(ApiError.Timeout))

        val state = viewModel(repository).state.value

        assertTrue(state.access is ScreenState.Content)
        assertEquals(ApiError.Timeout, (state.metrics as ScreenState.Error).failure.error)
    }

    @Test
    fun `an empty dashboard is an empty state, not an error`() = runTest {
        val repository = FakeBusinessRepository()
        repository.dashboardResult = ApiResult.Success(BusinessDashboard())

        assertTrue(viewModel(repository).state.value.metrics is ScreenState.Empty)
    }

    @Test
    fun `a staff member cannot open the menu section`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Success(
            access(role = PlaceStaffRole.Staff, category = PlaceCategory.Food),
        )
        val viewModel = viewModel(repository)

        val effects = mutableListOf<BusinessDashboardEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        viewModel.onEvent(BusinessDashboardEvent.SectionClicked(BusinessSection.Menu))

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `an owner opens the orders section`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessDashboardEvent.SectionClicked(BusinessSection.Orders))

        assertEquals(
            BusinessDashboardEffect.OpenOrders(PLACE, "Osh Markazi"),
            viewModel.effects.first(),
        )
    }

    /** У парикмахерской нет заказов — ручка еды к ней отношения не имеет. */
    @Test
    fun `a barbershop does not open the orders section`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Success(access(category = PlaceCategory.Master))
        val viewModel = viewModel(repository)

        val effects = mutableListOf<BusinessDashboardEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        viewModel.onEvent(BusinessDashboardEvent.SectionClicked(BusinessSection.Orders))

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a place under moderation opens no sections`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Success(
            access(status = PlaceModerationStatus.Pending),
        )
        val viewModel = viewModel(repository)

        val effects = mutableListOf<BusinessDashboardEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        viewModel.onEvent(BusinessDashboardEvent.SectionClicked(BusinessSection.Orders))

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `pausing sends the state known to the app and applies the answer`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessDashboardEvent.PauseToggled)

        assertEquals(listOf(PLACE to true), repository.paused)
        val access = (viewModel.state.value.access as ScreenState.Content).data
        assertFalse(access.isAvailable)
        assertFalse(viewModel.state.value.pauseInProgress)
    }

    @Test
    fun `a staff member cannot pause the place`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Success(access(role = PlaceStaffRole.Staff))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessDashboardEvent.PauseToggled)

        assertTrue(repository.paused.isEmpty())
    }

    @Test
    fun `a failed pause keeps the flag and shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.pauseResult = ApiResult.Failure(ApiFailure(ApiError.Forbidden))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessDashboardEvent.PauseToggled)

        val state = viewModel.state.value
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertTrue((state.access as ScreenState.Content).data.isAvailable)
    }

    @Test
    fun `returning to the screen re-reads both the access and the metrics`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessDashboardEvent.ScreenResumed)

        assertEquals(listOf(PLACE, PLACE), repository.accessRequests)
        assertEquals(listOf(PLACE, PLACE), repository.dashboardRequests)
    }

    /** Повтор метрик не трогает права: они уже подтверждены. */
    @Test
    fun `retrying the metrics does not ask for the access again`() = runTest {
        val repository = FakeBusinessRepository()
        repository.dashboardResult = ApiResult.Failure(ApiFailure(ApiError.Timeout))
        val viewModel = viewModel(repository)

        repository.dashboardResult = ApiResult.Success(
            BusinessDashboard.from(mapOf("orders" to 1L)),
        )
        viewModel.onEvent(BusinessDashboardEvent.RetryMetrics)

        assertEquals(listOf(PLACE), repository.accessRequests)
        assertEquals(listOf(PLACE, PLACE), repository.dashboardRequests)
        assertTrue(viewModel.state.value.metrics is ScreenState.Content)
    }

    @Test
    fun `the title comes from the server once the access is loaded`() = runTest {
        val repository = FakeBusinessRepository()
        repository.accessResult = ApiResult.Success(access(name = "Osh Markazi #2"))

        assertEquals("Osh Markazi #2", viewModel(repository).state.value.title)
    }

    private fun viewModel(repository: FakeBusinessRepository) = BusinessDashboardViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to PLACE,
                BusinessArgs.PLACE_NAME to "Osh Markazi",
            ),
        ),
    )

    private fun access(
        name: String = "Osh Markazi",
        category: PlaceCategory = PlaceCategory.Food,
        role: PlaceStaffRole = PlaceStaffRole.Owner,
        status: PlaceModerationStatus = PlaceModerationStatus.Active,
    ) = BusinessAccess(
        placeId = PLACE,
        placeName = name,
        category = category,
        role = role,
        status = status,
        isAvailable = true,
    )

    private companion object {
        const val PLACE = FakeBusinessRepository.PLACE_ID
    }
}
