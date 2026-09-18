package uz.mahalla.feature.role.ui.staff

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.feature.role.domain.isPlausibleUserId

/**
 * «Сотрудники» (issue #189): экран доступен только владельцу заведения,
 * граф ведёт сюда со «своих заведений» ([uz.mahalla.feature.role.ui.places.MyPlacesScreen]).
 *
 * @param pendingUserId сотрудник, на котором сейчас идёт запрос (смена роли
 * или удаление) — блокирует действия только над этой строкой, как
 * `pendingPlaceId` у «моих заведений».
 * @param actionFailure отказ смены роли или удаления — рядом со списком, а не
 * вместо него: строки уже на экране (issue #34).
 * @param confirmRemove сотрудник, которого собираются удалить: удаление
 * необратимо, поэтому кнопка сама по себе запрос не отправляет.
 */
data class PlaceStaffState(
    val placeId: String = "",
    val staff: ScreenState<List<PlaceStaffMember>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val pendingUserId: String? = null,
    val actionFailure: ApiFailure? = null,
    val confirmRemove: PlaceStaffMember? = null,
    val addForm: AddStaffFormState = AddStaffFormState(),
) : UiState

/**
 * Форма добавления. ID вводится вручную: контракт `place-staff-controller`
 * не даёт способа найти пользователя по телефону, поэтому [userIdError] —
 * единственная защита от опечатки до отправки запроса.
 */
data class AddStaffFormState(
    val visible: Boolean = false,
    val userId: String = "",
    val role: PlaceStaffRole = PlaceStaffRole.Staff,
    val validationShown: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val userIdError: Boolean get() = validationShown && !isPlausibleUserId(userId.trim())
}

sealed interface PlaceStaffEvent : UiEvent {
    data object Refreshed : PlaceStaffEvent
    data object Retry : PlaceStaffEvent

    data class RoleChangeRequested(val member: PlaceStaffMember, val role: PlaceStaffRole) : PlaceStaffEvent

    data class RemoveRequested(val member: PlaceStaffMember) : PlaceStaffEvent
    data object RemoveConfirmed : PlaceStaffEvent
    data object RemoveDismissed : PlaceStaffEvent

    data object AddSheetOpened : PlaceStaffEvent
    data object AddSheetDismissed : PlaceStaffEvent
    data class AddUserIdChanged(val userId: String) : PlaceStaffEvent
    data class AddRoleSelected(val role: PlaceStaffRole) : PlaceStaffEvent
    data object AddSubmitted : PlaceStaffEvent
}

/** Навигации со списка сотрудников нет — весь исход виден на самом экране. */
sealed interface PlaceStaffEffect : UiEffect
