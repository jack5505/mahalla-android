package uz.mahalla.feature.role.ui.staff

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.testutil.FakePlaceStaffRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * «Сотрудники» (issue #189).
 *
 * Robolectric нужен из-за `SavedStateHandle.toRoute()` — та же причина, что и
 * у карточки места ([uz.mahalla.feature.place.ui.PlaceDetailsViewModelTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceStaffViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun viewModel(repository: FakePlaceStaffRepository) = PlaceStaffViewModel(
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
        repository = repository,
    )

    @Test
    fun `the list loads for the place from the route`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Success(listOf(member("u-1")))

        val viewModel = viewModel(repository)

        assertEquals(listOf(PLACE_ID), repository.listedPlaceIds)
        val staff = (viewModel.state.value.staff as ScreenState.Content).data
        assertEquals(listOf("u-1"), staff.map(PlaceStaffMember::userId))
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        val repository = FakePlaceStaffRepository()

        val state = viewModel(repository).state.value

        assertTrue(state.staff is ScreenState.Empty)
    }

    @Test
    fun `a refusal is shown with the failure of the server`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Failure(ApiError.Forbidden)

        val state = viewModel(repository).state.value

        assertEquals(ApiError.Forbidden, (state.staff as ScreenState.Error).failure.error)
    }

    @Test
    fun `an invalid id is rejected before a request is sent`() = runTest {
        val repository = FakePlaceStaffRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.AddSheetOpened)
        viewModel.onEvent(PlaceStaffEvent.AddUserIdChanged("not-a-uuid"))
        viewModel.onEvent(PlaceStaffEvent.AddSubmitted)

        assertTrue(viewModel.state.value.addForm.userIdError)
        assertTrue(repository.added.isEmpty())
    }

    @Test
    fun `a valid id is added with the picked role and the sheet closes`() = runTest {
        val repository = FakePlaceStaffRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.AddSheetOpened)
        viewModel.onEvent(PlaceStaffEvent.AddUserIdChanged(VALID_UUID))
        viewModel.onEvent(PlaceStaffEvent.AddRoleSelected(PlaceStaffRole.Manager))
        viewModel.onEvent(PlaceStaffEvent.AddSubmitted)

        assertEquals(listOf(Triple(PLACE_ID, VALID_UUID, PlaceStaffRole.Manager)), repository.added)
        assertFalse(viewModel.state.value.addForm.visible)
        // Список перечитан у сервера — второй вызов `list`.
        assertEquals(listOf(PLACE_ID, PLACE_ID), repository.listedPlaceIds)
    }

    @Test
    fun `a refused add keeps the sheet open with the server's reason`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.addResult = ApiResult.Failure(ApiError.Business("USER_NOT_FOUND"))
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.AddSheetOpened)
        viewModel.onEvent(PlaceStaffEvent.AddUserIdChanged(VALID_UUID))
        viewModel.onEvent(PlaceStaffEvent.AddSubmitted)

        val form = viewModel.state.value.addForm
        assertTrue(form.visible)
        assertFalse(form.submitting)
        assertEquals(ApiError.Business("USER_NOT_FOUND"), form.failure?.error)
    }

    @Test
    fun `the sheet cannot be dismissed while a submission is in flight`() = runTest {
        val repository = FakePlaceStaffRepository()
        val gate = CompletableDeferred<Unit>()
        repository.addGate = gate
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.AddSheetOpened)
        viewModel.onEvent(PlaceStaffEvent.AddUserIdChanged(VALID_UUID))
        viewModel.onEvent(PlaceStaffEvent.AddSubmitted)
        // Запрос всё ещё летит (gate не отпущен) — закрытие шторки сейчас
        // завело бы вторую отправку поверх первой (issue #189, найдено ревью).
        viewModel.onEvent(PlaceStaffEvent.AddSheetDismissed)

        assertTrue(viewModel.state.value.addForm.visible)
        assertTrue(viewModel.state.value.addForm.submitting)

        gate.complete(Unit)
        assertFalse(viewModel.state.value.addForm.visible)
        assertEquals(1, repository.added.size)
    }

    @Test
    fun `the role is switched in place with what the server confirmed`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Success(listOf(member("u-1", PlaceStaffRole.Staff)))
        repository.changeRoleResult = ApiResult.Success(member("u-1", PlaceStaffRole.Manager))
        val viewModel = viewModel(repository)

        viewModel.onEvent(
            PlaceStaffEvent.RoleChangeRequested(member("u-1", PlaceStaffRole.Staff), PlaceStaffRole.Manager),
        )

        assertEquals(
            listOf(Triple(PLACE_ID, "u-1", PlaceStaffRole.Manager)),
            repository.changedRoles,
        )
        val staff = (viewModel.state.value.staff as ScreenState.Content).data
        assertEquals(PlaceStaffRole.Manager, staff.single().role)
        assertNull(viewModel.state.value.pendingUserId)
    }

    @Test
    fun `picking the role already assigned sends no request`() = runTest {
        val repository = FakePlaceStaffRepository()
        val viewModel = viewModel(repository)
        val current = member("u-1", PlaceStaffRole.Staff)

        viewModel.onEvent(PlaceStaffEvent.RoleChangeRequested(current, PlaceStaffRole.Staff))

        assertTrue(repository.changedRoles.isEmpty())
    }

    @Test
    fun `a refused role change keeps the old role and explains why`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Success(listOf(member("u-1", PlaceStaffRole.Staff)))
        repository.changeRoleResult = ApiResult.Failure(ApiError.Forbidden)
        val viewModel = viewModel(repository)

        viewModel.onEvent(
            PlaceStaffEvent.RoleChangeRequested(member("u-1", PlaceStaffRole.Staff), PlaceStaffRole.Owner),
        )

        val staff = (viewModel.state.value.staff as ScreenState.Content).data
        assertEquals(PlaceStaffRole.Staff, staff.single().role)
        assertEquals(ApiError.Forbidden, viewModel.state.value.actionFailure?.error)
    }

    @Test
    fun `removal asks for confirmation before the request goes out`() = runTest {
        val repository = FakePlaceStaffRepository()
        val viewModel = viewModel(repository)
        val target = member("u-1")

        viewModel.onEvent(PlaceStaffEvent.RemoveRequested(target))

        assertEquals(target, viewModel.state.value.confirmRemove)
        assertTrue(repository.removed.isEmpty())
    }

    @Test
    fun `dismissing the confirmation removes nobody`() = runTest {
        val repository = FakePlaceStaffRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.RemoveRequested(member("u-1")))
        viewModel.onEvent(PlaceStaffEvent.RemoveDismissed)

        assertNull(viewModel.state.value.confirmRemove)
        assertTrue(repository.removed.isEmpty())
    }

    @Test
    fun `a confirmed removal reloads the list from the server`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Success(listOf(member("u-1")))
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.RemoveRequested(member("u-1")))
        viewModel.onEvent(PlaceStaffEvent.RemoveConfirmed)

        assertEquals(listOf(PLACE_ID to "u-1"), repository.removed)
        assertNull(viewModel.state.value.confirmRemove)
        assertNull(viewModel.state.value.pendingUserId)
        // Перечитываем у сервера, а не вычёркиваем строку сами.
        assertEquals(listOf(PLACE_ID, PLACE_ID), repository.listedPlaceIds)
    }

    @Test
    fun `a refused removal keeps the member and explains why`() = runTest {
        val repository = FakePlaceStaffRepository()
        repository.listResult = ApiResult.Success(listOf(member("u-1")))
        repository.removeResult = ApiResult.Failure(ApiError.Forbidden)
        val viewModel = viewModel(repository)

        viewModel.onEvent(PlaceStaffEvent.RemoveRequested(member("u-1")))
        viewModel.onEvent(PlaceStaffEvent.RemoveConfirmed)

        val staff = (viewModel.state.value.staff as ScreenState.Content).data
        assertEquals(listOf("u-1"), staff.map(PlaceStaffMember::userId))
        assertEquals(ApiError.Forbidden, viewModel.state.value.actionFailure?.error)
        // Провал не перечитывает список — иначе отказ выглядел бы как успех.
        assertEquals(listOf(PLACE_ID), repository.listedPlaceIds)
    }

    private fun member(userId: String, role: PlaceStaffRole = PlaceStaffRole.Staff) =
        PlaceStaffMember(userId = userId, role = role)

    private companion object {
        const val PLACE_ID = "p-1"
        const val VALID_UUID = "8f14e45f-ceea-4a3d-8f1e-000000000001"
    }
}
