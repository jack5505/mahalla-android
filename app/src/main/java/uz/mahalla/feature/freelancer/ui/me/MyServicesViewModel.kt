package uz.mahalla.feature.freelancer.ui.me

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.map
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerProfileForm
import uz.mahalla.feature.freelancer.domain.FreelancerProfileFormValidator
import uz.mahalla.feature.freelancer.domain.FreelancerServiceForm
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormValidator
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import javax.inject.Inject

/**
 * «Мои услуги» — кабинет мастера (issue #71).
 *
 * Порядок здесь вынужденный, а не выбранный: сначала анкета
 * (`POST freelancers/me`), и только потом услуги — списка своих услуг у
 * бэкенда нет вовсе, они читаются общей ручкой `freelancers/{id}/services`, а
 * `id` берётся из анкеты. Поэтому без анкеты экран не показывает даже кнопку
 * «добавить услугу»: она кончилась бы отказом сервера.
 *
 * Ничего не кэшируется и после каждой правки список перечитывается у сервера:
 * `PUT toggle-availability` не сообщает нового значения (инвертирует сам), а
 * `DELETE` не говорит, снял он услугу или выключил. Догадываться об этом на
 * клиенте — верный способ показать мастеру не то, что видит клиент.
 */
@HiltViewModel
class MyServicesViewModel @Inject constructor(
    private val repository: FreelancerRepository,
    private val profileStore: UserProfileStore,
    private val phoneValidator: PhoneNumberValidator,
) : MviViewModel<MyServicesState, MyServicesEvent, MyServicesEffect>(MyServicesState()) {

    init {
        load()
    }

    override fun onEvent(event: MyServicesEvent) {
        when (event) {
            is MyServicesEvent.NameChanged -> updateForm { copy(name = event.name) }
            is MyServicesEvent.ProfessionChanged -> updateForm {
                copy(profession = event.profession)
            }

            is MyServicesEvent.CityChanged -> updateForm { copy(city = event.city) }
            is MyServicesEvent.BioChanged -> updateForm { copy(bio = event.bio) }

            is MyServicesEvent.PhoneChanged -> updateForm {
                copy(phoneDigits = phoneValidator.nationalDigits(event.digits))
            }

            // Числа чистятся на вводе: буква в ставке — это опечатка, а не
            // намерение, и показывать из-за неё ошибку незачем.
            is MyServicesEvent.HourlyRateChanged -> updateForm {
                copy(hourlyRateText = event.value.digitsOnly())
            }

            is MyServicesEvent.ExperienceChanged -> updateForm {
                copy(experienceYearsText = event.value.digitsOnly())
            }

            MyServicesEvent.EditProfileClicked -> updateState { copy(formOpen = true) }
            MyServicesEvent.CancelProfileEditClicked -> cancelProfileEdit()
            MyServicesEvent.SaveProfileClicked -> saveProfile()
            MyServicesEvent.AvailabilityToggled -> toggleAvailability()

            MyServicesEvent.AddServiceClicked -> openServiceForm(FreelancerServiceForm())
            is MyServicesEvent.EditServiceClicked -> {
                val service = currentState.services.dataOrEmpty()
                    .firstOrNull { it.id == event.serviceId }
                    ?: return
                openServiceForm(FreelancerServiceForm.of(service))
            }

            MyServicesEvent.ServiceFormDismissed -> updateState {
                copy(
                    serviceForm = null,
                    serviceErrors = emptyList(),
                    serviceValidationShown = false,
                    serviceFailure = null,
                )
            }

            is MyServicesEvent.ServiceTitleChanged -> updateServiceForm { copy(title = event.title) }
            is MyServicesEvent.ServiceDescriptionChanged -> updateServiceForm {
                copy(description = event.description)
            }

            is MyServicesEvent.ServicePriceChanged -> updateServiceForm {
                copy(priceText = event.value.digitsOnly())
            }

            is MyServicesEvent.ServiceDurationChanged -> updateServiceForm {
                copy(durationText = event.value.digitsOnly())
            }

            MyServicesEvent.SaveServiceClicked -> saveService()

            is MyServicesEvent.DeleteServiceClicked -> updateState {
                copy(
                    confirmDelete = services.dataOrEmpty().firstOrNull { it.id == event.serviceId },
                )
            }

            MyServicesEvent.DeleteConfirmed -> deleteService()
            MyServicesEvent.DeleteDismissed -> updateState { copy(confirmDelete = null) }

            MyServicesEvent.Retry -> load()
            MyServicesEvent.ServicesRetry -> loadServices()
        }
    }

    /**
     * Анкета, а за ней услуги. Форма каждый раз собирается заново из ответа
     * сервера: сюда приходят при открытии экрана, по «повторить» и после
     * сохранения — во всех трёх случаях правда именно у сервера.
     */
    private fun load() {
        updateState {
            copy(profile = ScreenState.Loading, profileFailure = null, availabilityFailure = null)
        }
        viewModelScope.launch {
            val account = profileStore.current()
            when (val result = repository.myProfile()) {
                is ApiResult.Failure -> updateState {
                    copy(profile = ScreenState.Error(result.failure), services = ScreenState.Empty)
                }

                is ApiResult.Success -> {
                    val freelancer = result.data
                    updateState {
                        copy(
                            profile = freelancer
                                ?.let { ScreenState.Content(it) }
                                ?: ScreenState.Empty,
                            form = formOf(freelancer, account),
                            formValidationShown = false,
                            // Анкеты нет — форма открыта сразу: заполнить её и
                            // есть единственное дело этого экрана.
                            formOpen = freelancer == null,
                        ).revalidated()
                    }
                    if (freelancer != null) loadServices() else updateState {
                        copy(services = ScreenState.Empty)
                    }
                }
            }
        }
    }

    private fun loadServices() {
        val freelancerId = currentState.freelancer?.id ?: return
        updateState { copy(services = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.myServices(freelancerId)
            updateState { copy(services = result.toListScreenState()) }
        }
    }

    /**
     * Анкета новая — поля берутся из аккаунта: имя и телефон человек уже
     * называл при входе, и набирать их заново — самый быстрый способ получить
     * брошенную форму (то же правило, что в анкете продавца, issue #84).
     *
     * У сохранённой анкеты выигрывает её собственный телефон: мастер мог
     * указать рабочий номер, а не тот, по которому вошёл.
     */
    private fun formOf(freelancer: Freelancer?, account: UserProfile): FreelancerProfileForm {
        val accountDigits = phoneValidator.nationalDigits(account.phone.orEmpty())
        if (freelancer == null) {
            return FreelancerProfileForm(
                name = account.fullName.orEmpty(),
                phoneDigits = accountDigits,
            )
        }
        val digits = phoneValidator.nationalDigits(freelancer.phone.orEmpty())
        return FreelancerProfileForm.of(
            freelancer = freelancer,
            phoneDigits = digits.ifEmpty { accountDigits },
        )
    }

    private fun updateForm(transform: FreelancerProfileForm.() -> FreelancerProfileForm) {
        // Правка стирает прошлый отказ сервера: сообщение о нём относилось бы
        // уже к другим данным (то же правило, что в анкете продавца).
        updateState { copy(form = form.transform(), profileFailure = null).revalidated() }
    }

    private fun MyServicesState.revalidated(): MyServicesState =
        copy(formErrors = FreelancerProfileFormValidator.validate(form, phoneValidator::isValid))

    /** «Отмена» возвращает поля к сохранённому: правку никто не подтверждал. */
    private fun cancelProfileEdit() {
        val freelancer = currentState.freelancer ?: return
        viewModelScope.launch {
            val account = profileStore.current()
            updateState {
                copy(
                    form = formOf(freelancer, account),
                    formOpen = false,
                    formValidationShown = false,
                    profileFailure = null,
                ).revalidated()
            }
        }
    }

    /**
     * Сохранение анкеты. После успеха экран **перечитывает** её у сервера:
     * `POST freelancers/me` отвечает анкетой, но она может приехать без `id`,
     * а по нему грузятся услуги — и «сохранено» превратилось бы в ошибку
     * разбора на ровном месте.
     */
    private fun saveProfile() {
        val state = currentState.revalidated()
        if (state.formErrors.isNotEmpty()) {
            updateState { state.copy(formValidationShown = true) }
            return
        }
        if (state.savingProfile) return

        updateState { state.copy(savingProfile = true, profileFailure = null) }
        viewModelScope.launch {
            when (val result = repository.saveMyProfile(state.form)) {
                is ApiResult.Failure -> updateState {
                    copy(savingProfile = false, profileFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(savingProfile = false, formOpen = false) }
                    load()
                }
            }
        }
    }

    /**
     * «Принимаю заказы». Новое значение задаёт сервер (ручка инвертирует флаг
     * сама), поэтому переключатель сначала показывает ожидаемое, а потом —
     * перечитанное: если перечитать не вышло, остаётся ожидаемое, ведь сервер
     * переключение уже принял.
     */
    private fun toggleAvailability() {
        if (currentState.freelancer == null || currentState.togglingAvailability) return

        updateState { copy(togglingAvailability = true, availabilityFailure = null) }
        viewModelScope.launch {
            when (val result = repository.toggleAvailability()) {
                is ApiResult.Failure -> updateState {
                    copy(togglingAvailability = false, availabilityFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(profile = profile.map { it.copy(isAvailable = !it.isAvailable) })
                    }
                    val reread = repository.myProfile()
                    updateState {
                        copy(
                            togglingAvailability = false,
                            profile = (reread as? ApiResult.Success)
                                ?.data
                                ?.let { ScreenState.Content(it) }
                                ?: profile,
                        )
                    }
                }
            }
        }
    }

    private fun openServiceForm(form: FreelancerServiceForm) {
        updateState {
            copy(
                serviceForm = form,
                serviceErrors = FreelancerServiceFormValidator.validate(form),
                serviceValidationShown = false,
                serviceFailure = null,
            )
        }
    }

    private fun updateServiceForm(transform: FreelancerServiceForm.() -> FreelancerServiceForm) {
        val form = currentState.serviceForm?.transform() ?: return
        updateState {
            copy(
                serviceForm = form,
                serviceErrors = FreelancerServiceFormValidator.validate(form),
                serviceFailure = null,
            )
        }
    }

    private fun saveService() {
        val state = currentState
        val form = state.serviceForm ?: return
        val errors = FreelancerServiceFormValidator.validate(form)
        if (errors.isNotEmpty()) {
            updateState { copy(serviceErrors = errors, serviceValidationShown = true) }
            return
        }
        if (state.savingService) return

        updateState { copy(savingService = true, serviceFailure = null) }
        viewModelScope.launch {
            when (val result = repository.saveMyService(form)) {
                is ApiResult.Failure -> updateState {
                    copy(savingService = false, serviceFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            savingService = false,
                            serviceForm = null,
                            serviceErrors = emptyList(),
                            serviceValidationShown = false,
                        )
                    }
                    loadServices()
                }
            }
        }
    }

    private fun deleteService() {
        val service = currentState.confirmDelete ?: return
        updateState { copy(confirmDelete = null, deletingServiceId = service.id) }
        viewModelScope.launch {
            when (val result = repository.deleteMyService(service.id)) {
                is ApiResult.Failure -> updateState {
                    copy(deletingServiceId = null, serviceFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(deletingServiceId = null) }
                    loadServices()
                }
            }
        }
    }
}

/** Список услуг, когда он есть; во всех прочих состояниях — пустой. */
private fun ScreenState<List<BarberService>>.dataOrEmpty(): List<BarberService> =
    (this as? ScreenState.Content)?.data.orEmpty()

/**
 * Ставку, стаж, цену и длительность набирают цифрами: клавиатура числовая, но
 * вставкой из буфера туда попадает что угодно.
 */
private fun String.digitsOnly(): String = filter(Char::isDigit)
