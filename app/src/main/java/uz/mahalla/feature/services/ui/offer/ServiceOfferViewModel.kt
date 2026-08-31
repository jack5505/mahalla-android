package uz.mahalla.feature.services.ui.offer

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.services.data.ServicesRepository
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferForm
import uz.mahalla.feature.services.domain.ServiceOfferValidator
import javax.inject.Inject

/**
 * Выставление услуги (issue #71): кто вы, чем помогаете и почём.
 *
 * Анкета читается у сервера при открытии — она общая для всех устройств, и
 * локальной копии у приложения нет. «Анкеты ещё нет» (`null`) — не ошибка, а
 * первый вход в форму: тогда имя и номер подставляются из аккаунта, чтобы не
 * набирать их заново.
 */
@HiltViewModel
class ServiceOfferViewModel @Inject constructor(
    private val repository: ServicesRepository,
    private val profileStore: UserProfileStore,
    private val phoneNumbers: PhoneNumberValidator,
) : MviViewModel<ServiceOfferState, ServiceOfferEvent, ServiceOfferEffect>(ServiceOfferState()) {

    init {
        load()
    }

    override fun onEvent(event: ServiceOfferEvent) {
        when (event) {
            is ServiceOfferEvent.NameChanged -> updateForm { copy(name = event.name) }

            is ServiceOfferEvent.ProfessionChanged ->
                updateForm { copy(profession = event.profession) }

            is ServiceOfferEvent.CityChanged -> updateForm { copy(city = event.city) }
            is ServiceOfferEvent.BioChanged -> updateForm { copy(bio = event.bio) }
            is ServiceOfferEvent.PhoneChanged -> updateForm { copy(phoneDigits = event.digits) }

            // Цифры отсеиваются здесь, а не валидатором: цену набирают на
            // цифровой клавиатуре, и запятая с пробелом попадают туда случайно.
            is ServiceOfferEvent.RateChanged ->
                updateForm { copy(hourlyRate = event.rate.filter(Char::isDigit)) }

            is ServiceOfferEvent.ExperienceChanged ->
                updateForm { copy(experienceYears = event.years.filter(Char::isDigit)) }

            ServiceOfferEvent.SaveClicked -> save()
            ServiceOfferEvent.AvailabilityToggled -> toggleAvailability()
            ServiceOfferEvent.RetryRequested -> load()
            ServiceOfferEvent.BackClicked -> emitEffect(ServiceOfferEffect.NavigateBack)
        }
    }

    private fun load() {
        updateState { copy(isLoading = true, loadFailure = null) }
        viewModelScope.launch {
            when (val result = repository.myOffer()) {
                is ApiResult.Success -> {
                    val offer = result.data
                    val form = offer?.let { ServiceOfferForm.of(it, phoneNumbers) }
                        ?: formFromAccount()
                    updateState {
                        copy(isLoading = false, offer = offer, form = form).revalidated()
                    }
                }

                is ApiResult.Failure -> updateState {
                    copy(isLoading = false, loadFailure = result.failure)
                }
            }
        }
    }

    /**
     * Первый вход в форму: имя и номер берутся из аккаунта — они уже известны,
     * и переписывать их вручную незачем. Профиль не приехал — форма пустая,
     * это не мешает её заполнить.
     */
    private suspend fun formFromAccount(): ServiceOfferForm {
        val profile = profileStore.current()
        return ServiceOfferForm(
            name = profile.fullName?.trim().orEmpty(),
            phoneDigits = profile.phone?.let(phoneNumbers::nationalDigits).orEmpty(),
        )
    }

    private fun updateForm(transform: ServiceOfferForm.() -> ServiceOfferForm) {
        updateState {
            copy(form = form.transform(), saveFailure = null, saved = false).revalidated()
        }
    }

    private fun ServiceOfferState.revalidated(): ServiceOfferState =
        copy(errors = ServiceOfferValidator.validate(form, phoneNumbers))

    private fun save() {
        val state = currentState
        if (state.isSaving) return
        if (state.errors.isNotEmpty()) {
            // Ошибки уже посчитаны — нажатие только делает их видимыми.
            updateState { copy(validationShown = true) }
            return
        }
        updateState { copy(isSaving = true, saveFailure = null, saved = false, validationShown = true) }
        viewModelScope.launch {
            when (val result = repository.saveOffer(state.form)) {
                is ApiResult.Success -> updateState {
                    // Форма пересобирается из ответа: сервер мог обрезать или
                    // нормализовать поля, и показывать надо то, что он принял.
                    copy(
                        isSaving = false,
                        saved = true,
                        offer = result.data,
                        form = ServiceOfferForm.of(result.data, phoneNumbers),
                    ).revalidated()
                }

                is ApiResult.Failure -> updateState {
                    copy(isSaving = false, saveFailure = result.failure)
                }
            }
        }
    }

    /**
     * «Принимаю заказы» переключает сервер, а не клиент: `toggle-availability`
     * не принимает желаемое значение, он меняет его на противоположное. Поэтому
     * после успеха анкета перечитывается — так же, как список устройств после
     * доверия (issue #61), — а не правится по месту.
     */
    private fun toggleAvailability() {
        val offer: ServiceOffer = currentState.offer ?: return
        if (currentState.availabilityPending) return
        updateState { copy(availabilityPending = true, saveFailure = null, saved = false) }
        viewModelScope.launch {
            when (val result = repository.toggleAvailability()) {
                is ApiResult.Success -> {
                    val reloaded = repository.myOffer()
                    updateState {
                        copy(
                            availabilityPending = false,
                            offer = (reloaded as? ApiResult.Success)?.data
                            // Перечитать не удалось — переключатель всё равно
                            // сдвинулся: запрос сервер принял.
                                ?: offer.copy(isAvailable = !offer.isAvailable),
                        )
                    }
                }

                is ApiResult.Failure -> updateState {
                    copy(availabilityPending = false, saveFailure = result.failure)
                }
            }
        }
    }
}
