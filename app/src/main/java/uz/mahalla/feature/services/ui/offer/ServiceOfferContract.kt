package uz.mahalla.feature.services.ui.offer

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferError
import uz.mahalla.feature.services.domain.ServiceOfferForm

/**
 * Форма выставления услуги (issue #71).
 *
 * [offer] — анкета, которую знает сервер: `null` значит «услуг ещё не
 * выставляли», и тогда форма пустая, а переключателя «принимаю заказы» нет —
 * переключать нечего.
 *
 * [saved] показывается после успешного сохранения и снимается первым же
 * изменением формы: «сохранено» поверх изменённых полей — вранье.
 */
data class ServiceOfferState(
    val isLoading: Boolean = true,
    val loadFailure: ApiFailure? = null,
    val offer: ServiceOffer? = null,
    val form: ServiceOfferForm = ServiceOfferForm(),
    val errors: List<ServiceOfferError> = emptyList(),
    val validationShown: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailure: ApiFailure? = null,
    val saved: Boolean = false,
    val availabilityPending: Boolean = false,
) : UiState {

    val canSave: Boolean get() = errors.isEmpty() && !isSaving

    private val visibleErrors: List<ServiceOfferError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (ServiceOfferError) -> Boolean): ServiceOfferError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface ServiceOfferEvent : UiEvent {
    data class NameChanged(val name: String) : ServiceOfferEvent
    data class ProfessionChanged(val profession: String) : ServiceOfferEvent
    data class CityChanged(val city: String) : ServiceOfferEvent
    data class BioChanged(val bio: String) : ServiceOfferEvent

    /** Девять национальных цифр без `+998` — как во всём онбординге. */
    data class PhoneChanged(val digits: String) : ServiceOfferEvent

    data class RateChanged(val rate: String) : ServiceOfferEvent
    data class ExperienceChanged(val years: String) : ServiceOfferEvent

    data object SaveClicked : ServiceOfferEvent
    data object AvailabilityToggled : ServiceOfferEvent
    data object RetryRequested : ServiceOfferEvent
    data object BackClicked : ServiceOfferEvent
}

sealed interface ServiceOfferEffect : UiEffect {
    data object NavigateBack : ServiceOfferEffect
}
