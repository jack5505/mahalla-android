package uz.mahalla.feature.freelancer.ui.cabinet

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormError

/**
 * Кабинет мастера (issue #190).
 *
 * @param profile [ScreenState.Empty] означает «анкеты нет» — тот самый риск
 * из issue #190 (`404` или `200` с пустым `data`, репозиторий сводит оба к
 * одному), и экран должен показать «стать мастером», а не ошибку.
 * @param togglingAvailability запрос переключателя уже идёт — блокирует его
 * точечно, как в «моих заведениях» (issue #94).
 * @param services свои услуги — читаются только когда [profile] не пуст:
 * без `id` мастера звать `GET freelancers/{id}/services` нечем.
 * @param pendingServiceId услуга, у которой сейчас идёт запрос (удаление или
 * сохранение формы) — остальные строки в это время не трогаем.
 * @param serviceSheet шторка добавления/правки услуги; `null` — закрыта.
 * @param confirmDeleteService услуга, которую собираются удалить — подтверждение
 * диалогом, как отзыв устройства в профиле (issue #61).
 * @param orders входящие заказы, страницами — та же механика догрузки, что у
 * [uz.mahalla.feature.freelancer.ui.orders.MyFreelancerOrdersContract].
 * @param pendingOrderId заказ, у которого сейчас меняется статус.
 */
data class FreelancerCabinetState(
    val profile: ScreenState<Freelancer> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val togglingAvailability: Boolean = false,
    val availabilityFailure: ApiFailure? = null,

    val services: ScreenState<List<FreelancerCabinetService>> = ScreenState.Loading,
    val pendingServiceId: String? = null,
    val servicesActionFailure: ApiFailure? = null,
    val serviceSheet: FreelancerServiceSheetState? = null,
    val confirmDeleteService: FreelancerCabinetService? = null,

    val orders: ScreenState<List<FreelancerOrder>> = ScreenState.Loading,
    val hasMoreOrders: Boolean = false,
    val isLoadingMoreOrders: Boolean = false,
    val loadMoreOrdersFailure: ApiFailure? = null,
    val pendingOrderId: String? = null,
    val ordersActionFailure: ApiFailure? = null,
) : UiState

/**
 * Форма добавления/правки услуги (issue #190). [serviceId] — `null` в режиме
 * добавления, и тогда сабмит уходит в `POST`, иначе — в `PUT` с этим id.
 */
data class FreelancerServiceSheetState(
    val serviceId: String? = null,
    val draft: FreelancerServiceDraft = FreelancerServiceDraft(),
    val errors: List<FreelancerServiceFormError> = emptyList(),
    val validationShown: Boolean = false,
    val submitting: Boolean = false,
    val submitError: ApiFailure? = null,
) {
    val isEditing: Boolean get() = serviceId != null

    val visibleErrors: List<FreelancerServiceFormError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (FreelancerServiceFormError) -> Boolean): FreelancerServiceFormError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface FreelancerCabinetEvent : UiEvent {
    data object ScreenResumed : FreelancerCabinetEvent
    data object Refreshed : FreelancerCabinetEvent
    data object Retry : FreelancerCabinetEvent

    data object BecomeMasterClicked : FreelancerCabinetEvent
    data object EditAnketaClicked : FreelancerCabinetEvent
    data object AvailabilityToggled : FreelancerCabinetEvent

    data object ServiceAddClicked : FreelancerCabinetEvent
    data class ServiceEditClicked(val service: FreelancerCabinetService) : FreelancerCabinetEvent
    data object ServiceSheetDismissed : FreelancerCabinetEvent
    data class ServiceTitleChanged(val title: String) : FreelancerCabinetEvent
    data class ServiceDescriptionChanged(val description: String) : FreelancerCabinetEvent
    data class ServicePriceChanged(val price: String) : FreelancerCabinetEvent
    data class ServiceDurationChanged(val duration: String) : FreelancerCabinetEvent
    data class ServiceActiveChanged(val active: Boolean) : FreelancerCabinetEvent
    data object ServiceSheetSubmitted : FreelancerCabinetEvent
    data class ServiceDeleteRequested(val service: FreelancerCabinetService) : FreelancerCabinetEvent
    data object ServiceDeleteConfirmed : FreelancerCabinetEvent
    data object ServiceDeleteDismissed : FreelancerCabinetEvent

    data object OrdersRetry : FreelancerCabinetEvent
    data object LoadMoreOrders : FreelancerCabinetEvent
    data class OrderStatusChanged(
        val orderId: String,
        val status: FreelancerOrderStatus,
    ) : FreelancerCabinetEvent
}

sealed interface FreelancerCabinetEffect : UiEffect {
    /** «Стать мастером» и «редактировать анкету» ведут на одну и ту же форму. */
    data object OpenAnketaForm : FreelancerCabinetEffect
}
