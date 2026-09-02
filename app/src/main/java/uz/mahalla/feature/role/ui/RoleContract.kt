package uz.mahalla.feature.role.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.role.domain.UserRole

/**
 * Выбор роли (issue #84).
 *
 * @param selected что выбрано на экране прямо сейчас.
 * @param saved что уже сохранено: «Продолжить» ведёт в анкету, а строка в
 * профиле должна показывать текущий выбор, а не последнее касание.
 * @param customerFilled анкета покупателя заполнена — на карточке видна
 * отметка, иначе непонятно, вернуться в неё или заполнять заново.
 * @param busy идёт запись выбора: второй тап не должен открывать анкету
 * дважды.
 */
data class RoleState(
    val selected: UserRole? = null,
    val saved: UserRole? = null,
    val customerFilled: Boolean = false,
    val busy: Boolean = false,
) : UiState {

    /** Пока роль не выбрана, вести некуда: кнопка выключена. */
    val canContinue: Boolean get() = selected != null && !busy
}

sealed interface RoleEvent : UiEvent {
    data class RoleSelected(val role: UserRole) : RoleEvent
    data object ContinueClicked : RoleEvent
}

sealed interface RoleEffect : UiEffect {
    /**
     * Куда идти дальше, решает граф: ViewModel про маршруты не знает, а
     * поведение «сохранил и вышел» различается в онбординге и в профиле.
     */
    data object OpenCustomerForm : RoleEffect
    data object OpenProviderForm : RoleEffect
}
