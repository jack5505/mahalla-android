package uz.mahalla.feature.freelancer.ui.me

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerProfileForm
import uz.mahalla.feature.freelancer.domain.FreelancerProfileFormError
import uz.mahalla.feature.freelancer.domain.FreelancerServiceForm
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormError

/**
 * «Мои услуги» — кабинет мастера (issue #71): анкета исполнителя, переключатель
 * «принимаю заказы» и услуги, которые мастер выставляет клиентам.
 *
 * Вторая из двух форм этого issue. Первая — форма заказа
 * (`FreelancerProfileScreen`, issue #107): там клиент выбирает выставленную
 * здесь услугу. Обе стороны сделки поэтому живут в одной вертикали, а не в
 * разных: услуга у них одна и та же.
 *
 * Анкета и услуги — на одном экране и в таком порядке не случайно: услугу
 * нельзя выставить, пока нет анкеты (её `id` — путь к списку услуг), и
 * человеку это должно быть видно, а не выясняться отказом сервера.
 *
 * @param profile анкета с сервера. [ScreenState.Empty] — **анкеты ещё нет**
 * (`404` от `freelancers/me`), и это не ошибка: экран тогда показывает пустую
 * форму «стать мастером».
 * @param form то, что сейчас в полях анкеты. Отдельно от [profile]: правку
 * нельзя терять из-за перечитывания, а сравнение «что было / что стало»
 * показывает, есть ли несохранённое.
 * @param formOpen форма анкеты раскрыта. Пока анкеты нет — всегда: заполнить
 * её и есть единственное дело этого экрана.
 * @param services свои услуги, **включая выключенные**: это состав, который
 * мастер видит про себя, а не витрина для клиента.
 * @param serviceForm открытая форма услуги: новая ([FreelancerServiceForm.isNew])
 * или правка выставленной. `null` — форма закрыта.
 * @param confirmDelete услуга, которую собираются снять. Спрашиваем: услугу с
 * описанием и ценой набирали руками, а вернуть её нечем.
 */
data class MyServicesState(
    val profile: ScreenState<Freelancer> = ScreenState.Loading,
    val form: FreelancerProfileForm = FreelancerProfileForm(),
    val formErrors: List<FreelancerProfileFormError> = emptyList(),
    val formValidationShown: Boolean = false,
    val formOpen: Boolean = false,
    val savingProfile: Boolean = false,
    val profileFailure: ApiFailure? = null,
    val togglingAvailability: Boolean = false,
    val availabilityFailure: ApiFailure? = null,
    val services: ScreenState<List<BarberService>> = ScreenState.Loading,
    val serviceForm: FreelancerServiceForm? = null,
    val serviceErrors: List<FreelancerServiceFormError> = emptyList(),
    val serviceValidationShown: Boolean = false,
    val savingService: Boolean = false,
    val serviceFailure: ApiFailure? = null,
    val confirmDelete: BarberService? = null,
    val deletingServiceId: String? = null,
) : UiState {

    val freelancer: Freelancer? get() = profile.dataOrNull()

    /** Анкета прочитана, и её нет: `404` — это ответ, а не отказ. */
    val hasNoProfile: Boolean get() = profile is ScreenState.Empty

    /** Пока анкеты нет, выставлять услуги некуда — их и не показываем. */
    val canManageServices: Boolean get() = freelancer != null

    val visibleFormErrors: List<FreelancerProfileFormError>
        get() = if (formValidationShown) formErrors else emptyList()

    val visibleServiceErrors: List<FreelancerServiceFormError>
        get() = if (serviceValidationShown) serviceErrors else emptyList()

    fun formError(
        predicate: (FreelancerProfileFormError) -> Boolean,
    ): FreelancerProfileFormError? = visibleFormErrors.firstOrNull(predicate)

    fun serviceError(
        predicate: (FreelancerServiceFormError) -> Boolean,
    ): FreelancerServiceFormError? = visibleServiceErrors.firstOrNull(predicate)
}

sealed interface MyServicesEvent : UiEvent {

    /** Анкета. */
    data class NameChanged(val name: String) : MyServicesEvent
    data class ProfessionChanged(val profession: String) : MyServicesEvent
    data class CityChanged(val city: String) : MyServicesEvent
    data class BioChanged(val bio: String) : MyServicesEvent
    data class PhoneChanged(val digits: String) : MyServicesEvent
    data class HourlyRateChanged(val value: String) : MyServicesEvent
    data class ExperienceChanged(val value: String) : MyServicesEvent

    data object EditProfileClicked : MyServicesEvent
    data object CancelProfileEditClicked : MyServicesEvent
    data object SaveProfileClicked : MyServicesEvent
    data object AvailabilityToggled : MyServicesEvent

    /** Услуги. */
    data object AddServiceClicked : MyServicesEvent
    data class EditServiceClicked(val serviceId: String) : MyServicesEvent
    data object ServiceFormDismissed : MyServicesEvent
    data class ServiceTitleChanged(val title: String) : MyServicesEvent
    data class ServiceDescriptionChanged(val description: String) : MyServicesEvent
    data class ServicePriceChanged(val value: String) : MyServicesEvent
    data class ServiceDurationChanged(val value: String) : MyServicesEvent
    data object SaveServiceClicked : MyServicesEvent

    data class DeleteServiceClicked(val serviceId: String) : MyServicesEvent
    data object DeleteConfirmed : MyServicesEvent
    data object DeleteDismissed : MyServicesEvent

    data object Retry : MyServicesEvent
    data object ServicesRetry : MyServicesEvent
}

/** У экрана нет переходов наружу: «назад» ведёт в профиль, откуда пришли. */
sealed interface MyServicesEffect : UiEffect
