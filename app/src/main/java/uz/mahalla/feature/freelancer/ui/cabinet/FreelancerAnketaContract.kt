package uz.mahalla.feature.freelancer.ui.cabinet

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaFormError

/**
 * Анкета мастера (issue #190): создать или поправить профиль в кабинете.
 *
 * @param editing анкета уже существует, форма открыта на правку — влияет
 * только на подпись кнопки: запрос один и тот же (`POST freelancers/me`).
 * @param submitError отказ бэкенда: текстом сервера рядом с кнопкой
 * (issue #34), форма остаётся заполненной.
 */
data class FreelancerAnketaState(
    val form: FreelancerAnketaForm = FreelancerAnketaForm(),
    val editing: Boolean = false,
    val errors: List<FreelancerAnketaFormError> = emptyList(),
    val validationShown: Boolean = false,
    val submitting: Boolean = false,
    val submitError: ApiFailure? = null,
) : UiState {

    val visibleErrors: List<FreelancerAnketaFormError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (FreelancerAnketaFormError) -> Boolean): FreelancerAnketaFormError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface FreelancerAnketaEvent : UiEvent {
    data class NameChanged(val name: String) : FreelancerAnketaEvent
    data class ProfessionChanged(val profession: String) : FreelancerAnketaEvent
    data class BioChanged(val bio: String) : FreelancerAnketaEvent
    data class CityChanged(val city: String) : FreelancerAnketaEvent
    data class PhoneChanged(val digits: String) : FreelancerAnketaEvent
    data class HourlyRateChanged(val text: String) : FreelancerAnketaEvent
    data class ExperienceYearsChanged(val text: String) : FreelancerAnketaEvent
    data object SubmitClicked : FreelancerAnketaEvent
}

sealed interface FreelancerAnketaEffect : UiEffect {
    /** Анкета принята — кабинет перечитает профиль сам при возврате. */
    data class Submitted(val profile: Freelancer) : FreelancerAnketaEffect
}
