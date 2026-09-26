package uz.mahalla.feature.role.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.discovery.data.CategoryRepository
import uz.mahalla.feature.discovery.domain.PlaceCategory
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
    private val categoryRepository: CategoryRepository,
) : MviViewModel<ProviderFormState, ProviderFormEvent, ProviderFormEffect>(ProviderFormState()) {

    init {
        // Выбор категории — из тех, что включены в дашборде (issue #378):
        // заведение выключенной категории в каталоге всё равно не покажут.
        // Список для показа берётся из кэша сразу, а снимать уже сделанный
        // выбор можно только по подтверждённому серверу списку (issue #382).
        viewModelScope.launch {
            categoryRepository.categories().collect { list -> updateState { copy(categories = list) } }
        }
        viewModelScope.launch {
            categoryRepository.confirmedCategories().collect(::dropDisabledCategory)
        }
        // Анкету открывают из профиля, минуя главную, — кэш обновляет она сама.
        viewModelScope.launch { categoryRepository.refresh() }
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

            is ProviderFormEvent.LocationPicked -> updateForm { copy(location = event.point) }

            ProviderFormEvent.PickLocationClicked ->
                emitEffect(ProviderFormEffect.OpenMapPicker(currentState.form.location))

            ProviderFormEvent.SubmitClicked -> submit()
            ProviderFormEvent.DoneClicked -> emitEffect(ProviderFormEffect.Finished)
        }
    }

    /**
     * Категорию выбрали, а дашборд её тем временем выключил: чип из списка
     * пропал, и оставлять выбор нельзя — заявка ушла бы с категорией, которой
     * в каталоге нет, а снять её в форме было бы нечем (issue #382).
     *
     * Ошибку валидации это не показывает: [ProviderFormState.validationShown]
     * поднимает только «Отправить». Человек увидит пустой выбор, а не упрёк за
     * то, чего не делал.
     */
    private fun dropDisabledCategory(enabled: List<PlaceCategory>) {
        val selected = currentState.form.category ?: return
        if (selected in enabled) return
        // submitError гасится по тому же правилу, что в updateForm (issue #76):
        // форма изменилась, и прошлый отказ сервера относится уже к другим
        // данным — неважно, правил её человек или кэш.
        updateState { copy(form = form.copy(category = null), submitError = null).revalidated() }
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
