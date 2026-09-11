package uz.mahalla.feature.freelancer.ui.cabinet

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.freelancer.data.FreelancerCabinetRepository
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaFormValidator
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import javax.inject.Inject

/**
 * Анкета мастера (issue #190).
 *
 * Своя анкета подгружается в `init`, а не приходит аргументом маршрута: у
 * `FreelancerAnketaRoute` их нет — экран открывают и с пустого кабинета
 * (создание), и с заполненного (правка), и в обоих случаях достаточно того
 * же `GET freelancers/me`, которым кабинет уже умеет пользоваться.
 */
@HiltViewModel
class FreelancerAnketaViewModel @Inject constructor(
    private val repository: FreelancerCabinetRepository,
    private val phoneValidator: PhoneNumberValidator,
) : MviViewModel<FreelancerAnketaState, FreelancerAnketaEvent, FreelancerAnketaEffect>(
    FreelancerAnketaState(),
) {

    init {
        viewModelScope.launch {
            when (val result = repository.me()) {
                is ApiResult.Success -> result.data?.let { profile ->
                    updateState {
                        copy(
                            editing = true,
                            form = FreelancerAnketaForm(
                                name = profile.name,
                                profession = profile.profession.orEmpty(),
                                bio = profile.bio.orEmpty(),
                                city = profile.city.orEmpty(),
                                phoneDigits = phoneValidator.nationalDigits(profile.phone.orEmpty()),
                                hourlyRateText = profile.hourlyRateSum.takeIf { it > 0 }
                                    ?.toString().orEmpty(),
                                experienceYearsText = profile.experienceYears?.takeIf { it > 0 }
                                    ?.toString().orEmpty(),
                            ),
                        )
                    }
                }

                // Анкеты ещё нет или перечитать не удалось — форма остаётся
                // пустой: это ровно тот случай, ради которого экран и открыт
                // («стать мастером»), молчаливый отказ здесь не помеха вводу.
                is ApiResult.Failure -> Unit
            }
        }
    }

    override fun onEvent(event: FreelancerAnketaEvent) {
        when (event) {
            is FreelancerAnketaEvent.NameChanged -> updateForm { copy(name = event.name) }
            is FreelancerAnketaEvent.ProfessionChanged ->
                updateForm { copy(profession = event.profession) }

            is FreelancerAnketaEvent.BioChanged -> updateForm { copy(bio = event.bio) }
            is FreelancerAnketaEvent.CityChanged -> updateForm { copy(city = event.city) }

            is FreelancerAnketaEvent.PhoneChanged -> updateForm {
                copy(phoneDigits = phoneValidator.nationalDigits(event.digits))
            }

            is FreelancerAnketaEvent.HourlyRateChanged ->
                updateForm { copy(hourlyRateText = event.text) }

            is FreelancerAnketaEvent.ExperienceYearsChanged ->
                updateForm { copy(experienceYearsText = event.text) }

            FreelancerAnketaEvent.SubmitClicked -> submit()
        }
    }

    private fun updateForm(transform: FreelancerAnketaForm.() -> FreelancerAnketaForm) {
        // Правка стирает прошлый отказ сервера — он относился к другим
        // данным (то же правило, что в анкете продавца, issue #84).
        updateState { copy(form = form.transform(), submitError = null).revalidated() }
    }

    private fun FreelancerAnketaState.revalidated(): FreelancerAnketaState =
        copy(errors = FreelancerAnketaFormValidator.validate(form, phoneValidator::isValid))

    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.submitting) return

        updateState { state.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            when (val result = repository.submitProfile(state.form)) {
                is ApiResult.Failure -> updateState {
                    copy(submitting = false, submitError = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(submitting = false) }
                    emitEffect(FreelancerAnketaEffect.Submitted(result.data))
                }
            }
        }
    }
}
