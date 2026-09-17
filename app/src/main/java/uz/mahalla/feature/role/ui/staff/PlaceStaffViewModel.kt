package uz.mahalla.feature.role.ui.staff

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.role.data.PlaceStaffRepository
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.feature.role.domain.isPlausibleUserId
import uz.mahalla.navigation.PlaceStaffRoute
import javax.inject.Inject

/**
 * «Сотрудники» (issue #189): список, добавление по ID, смена роли и удаление
 * с подтверждением. Доступ проверяет бэкенд — экран открывается только со
 * «своих заведений», где владелец уже отфильтрован клиентом.
 */
@HiltViewModel
class PlaceStaffViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaceStaffRepository,
) : MviViewModel<PlaceStaffState, PlaceStaffEvent, PlaceStaffEffect>(
    PlaceStaffState(placeId = savedStateHandle.toRoute<PlaceStaffRoute>().placeId),
) {

    init {
        load()
    }

    override fun onEvent(event: PlaceStaffEvent) {
        when (event) {
            PlaceStaffEvent.Refreshed -> load(showLoading = false, refreshing = true)
            PlaceStaffEvent.Retry -> load()

            is PlaceStaffEvent.RoleChangeRequested -> changeRole(event.member, event.role)

            is PlaceStaffEvent.RemoveRequested -> updateState { copy(confirmRemove = event.member) }
            PlaceStaffEvent.RemoveDismissed -> updateState { copy(confirmRemove = null) }
            PlaceStaffEvent.RemoveConfirmed -> currentState.confirmRemove?.let(::remove)

            PlaceStaffEvent.AddSheetOpened -> updateState {
                copy(addForm = AddStaffFormState(visible = true))
            }

            // Пока запрос летит, закрыть шторку нельзя: `AddSheetOpened`
            // следом завёл бы вторую отправку поверх первой — она вернулась
            // бы позже и переписала состояние уже другой попытки.
            PlaceStaffEvent.AddSheetDismissed -> if (!currentState.addForm.submitting) {
                updateState { copy(addForm = addForm.copy(visible = false)) }
            }

            is PlaceStaffEvent.AddUserIdChanged -> updateState {
                copy(addForm = addForm.copy(userId = event.userId, failure = null))
            }

            is PlaceStaffEvent.AddRoleSelected -> updateState {
                copy(addForm = addForm.copy(role = event.role))
            }

            PlaceStaffEvent.AddSubmitted -> submitAdd()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        updateState {
            copy(
                staff = if (showLoading) ScreenState.Loading else staff,
                isRefreshing = refreshing,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.list(currentState.placeId)
            updateState { copy(staff = result.toListScreenState(), isRefreshing = false) }
        }
    }

    private fun submitAdd() {
        val form = currentState.addForm
        if (form.submitting) return
        val userId = form.userId.trim()
        if (!isPlausibleUserId(userId)) {
            updateState { copy(addForm = addForm.copy(validationShown = true)) }
            return
        }

        updateState { copy(addForm = addForm.copy(submitting = true, failure = null)) }
        viewModelScope.launch {
            when (val result = repository.add(currentState.placeId, userId, form.role)) {
                is ApiResult.Success -> {
                    updateState { copy(addForm = AddStaffFormState(visible = false)) }
                    // Перечитываем у сервера: список короткий, а на глаз
                    // вставить строку в правильное место незачем.
                    load(showLoading = false)
                }

                is ApiResult.Failure -> updateState {
                    copy(addForm = addForm.copy(submitting = false, failure = result.failure))
                }
            }
        }
    }

    private fun changeRole(member: PlaceStaffMember, role: PlaceStaffRole) {
        if (currentState.pendingUserId != null || member.role == role) return
        updateState { copy(pendingUserId = member.userId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.changeRole(currentState.placeId, member.userId, role)) {
                is ApiResult.Success -> updateState {
                    copy(pendingUserId = null, staff = staff.replacing(result.data))
                }

                is ApiResult.Failure -> updateState {
                    copy(pendingUserId = null, actionFailure = result.failure)
                }
            }
        }
    }

    /**
     * Список после удаления перечитывается у сервера, а не правится на
     * месте, — как и отзыв сессии устройства: провал не должен молча
     * вычеркнуть сотрудника, которого бэкенд на самом деле не удалил.
     */
    private fun remove(member: PlaceStaffMember) {
        if (currentState.pendingUserId != null) return
        updateState {
            copy(confirmRemove = null, pendingUserId = member.userId, actionFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.remove(currentState.placeId, member.userId)) {
                is ApiResult.Success -> {
                    updateState { copy(pendingUserId = null) }
                    load(showLoading = false)
                }

                is ApiResult.Failure -> updateState {
                    copy(pendingUserId = null, actionFailure = result.failure)
                }
            }
        }
    }

    private fun ScreenState<List<PlaceStaffMember>>.replacing(
        updated: PlaceStaffMember,
    ): ScreenState<List<PlaceStaffMember>> = (this as? ScreenState.Content)?.let { content ->
        ScreenState.Content(
            content.data.map { if (it.userId == updated.userId) updated else it },
        )
    } ?: this
}
