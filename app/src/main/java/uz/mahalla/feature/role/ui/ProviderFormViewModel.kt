package uz.mahalla.feature.role.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.data.ProviderRepository
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.ProviderFormValidator
import javax.inject.Inject

/**
 * Анкета продавца (issue #84): заявка на регистрацию заведения.
 *
 * Телефон и город подставляются из того, что приложение уже знает: номер — из
 * профиля аккаунта (по нему человек и вошёл), город — из настроек. Набирать
 * заново то, что уже введено, — самый быстрый способ получить брошенную
 * форму. Правки при этом никто не запрещает: заведение может стоять в другом
 * городе и отвечать по другому номеру.
 */
@HiltViewModel
class ProviderFormViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val roleRepository: RoleRepository,
    private val profileStore: UserProfileStore,
    private val phoneValidator: PhoneNumberValidator,
) : MviViewModel<ProviderFormState, ProviderFormEvent, ProviderFormEffect>(ProviderFormState()) {

    init {
        viewModelScope.launch {
            val city = roleRepository.current().customer.city
            val digits = phoneValidator.nationalDigits(profileStore.current().phone.orEmpty())
            updateState {
                copy(form = form.copy(city = form.city ?: city, phoneDigits = digits))
                    .revalidated()
            }
        }
    }

    override fun onEvent(event: ProviderFormEvent) {
        when (event) {
            is ProviderFormEvent.NameChanged -> updateForm { copy(name = event.name) }
            is ProviderFormEvent.CategorySelected -> updateForm { copy(category = event.category) }
            is ProviderFormEvent.CitySelected -> updateForm { copy(city = event.city) }
            is ProviderFormEvent.AddressChanged -> updateForm { copy(address = event.address) }

            is ProviderFormEvent.PhoneChanged -> updateForm {
                copy(phoneDigits = phoneValidator.nationalDigits(event.digits))
            }

            is ProviderFormEvent.DescriptionChanged -> updateForm {
                copy(description = event.description)
            }

            is ProviderFormEvent.WebsiteChanged -> updateForm { copy(website = event.website) }

            ProviderFormEvent.SubmitClicked -> submit()
            ProviderFormEvent.DoneClicked -> emitEffect(ProviderFormEffect.Finished)
        }
    }

    private fun updateForm(transform: ProviderForm.() -> ProviderForm) {
        // Правка стирает прошлый отказ сервера: сообщение о нём относилось бы
        // уже к другим данным (то же правило, что в форме отзыва, issue #76).
        updateState { copy(form = form.transform(), submitError = null).revalidated() }
    }

    private fun ProviderFormState.revalidated(): ProviderFormState =
        copy(errors = ProviderFormValidator.validate(form, phoneValidator::isValid))

    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.submitting) return

        updateState { state.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            when (val result = providerRepository.registerPlace(state.form)) {
                is ApiResult.Failure -> updateState {
                    copy(submitting = false, submitError = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(submitting = false, registered = result.data)
                }
            }
        }
    }
}
