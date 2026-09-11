package uz.mahalla.feature.freelancer.ui.cabinet

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.freelancer.data.FreelancerCabinetRepository
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormValidator
import javax.inject.Inject

/**
 * Кабинет мастера (issue #190): анкета, переключатель доступности, свои
 * услуги, входящие заказы — на одном экране, как в задаче.
 *
 * Всё перечитывается на каждом возврате ([FreelancerCabinetEvent.ScreenResumed]):
 * анкету могли поправить с другого устройства, статус заказа меняет клиент, а
 * состав услуг — только что закрытая форма. Показанное час назад после
 * возврата ничего не стоит (то же правило, что у «моих заведений», issue #94,
 * и у «моих заказов у мастеров», issue #107).
 */
@HiltViewModel
class FreelancerCabinetViewModel @Inject constructor(
    private val repository: FreelancerCabinetRepository,
) : MviViewModel<FreelancerCabinetState, FreelancerCabinetEvent, FreelancerCabinetEffect>(
    FreelancerCabinetState(),
) {

    private var loadMoreOrdersJob: Job? = null
    private var loadedOrdersPage = 0

    init {
        load()
    }

    override fun onEvent(event: FreelancerCabinetEvent) {
        when (event) {
            FreelancerCabinetEvent.ScreenResumed ->
                if (!currentState.profile.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            FreelancerCabinetEvent.Refreshed -> load(showLoading = false, refreshing = true)
            FreelancerCabinetEvent.Retry -> load()

            FreelancerCabinetEvent.BecomeMasterClicked,
            FreelancerCabinetEvent.EditAnketaClicked,
            -> emitEffect(FreelancerCabinetEffect.OpenAnketaForm)

            FreelancerCabinetEvent.AvailabilityToggled -> toggleAvailability()

            FreelancerCabinetEvent.ServiceAddClicked ->
                updateState { copy(serviceSheet = FreelancerServiceSheetState()) }

            is FreelancerCabinetEvent.ServiceEditClicked -> updateState {
                copy(
                    serviceSheet = FreelancerServiceSheetState(
                        serviceId = event.service.id,
                        draft = FreelancerServiceDraft.from(event.service),
                    ),
                )
            }

            FreelancerCabinetEvent.ServiceSheetDismissed ->
                updateState { copy(serviceSheet = null) }

            is FreelancerCabinetEvent.ServiceTitleChanged ->
                updateSheetDraft { copy(title = event.title) }

            is FreelancerCabinetEvent.ServiceDescriptionChanged ->
                updateSheetDraft { copy(description = event.description) }

            is FreelancerCabinetEvent.ServicePriceChanged ->
                updateSheetDraft { copy(priceText = event.price) }

            is FreelancerCabinetEvent.ServiceDurationChanged ->
                updateSheetDraft { copy(durationText = event.duration) }

            is FreelancerCabinetEvent.ServiceActiveChanged ->
                updateSheetDraft { copy(isActive = event.active) }

            FreelancerCabinetEvent.ServiceSheetSubmitted -> submitServiceSheet()

            is FreelancerCabinetEvent.ServiceDeleteRequested ->
                updateState { copy(confirmDeleteService = event.service) }

            FreelancerCabinetEvent.ServiceDeleteDismissed ->
                updateState { copy(confirmDeleteService = null) }

            FreelancerCabinetEvent.ServiceDeleteConfirmed -> deleteService()

            FreelancerCabinetEvent.OrdersRetry -> loadOrders()
            FreelancerCabinetEvent.LoadMoreOrders -> loadMoreOrders()
            is FreelancerCabinetEvent.OrderStatusChanged ->
                changeOrderStatus(event.orderId, event.status)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreOrdersJob?.cancel()
        loadedOrdersPage = 0
        updateState {
            copy(
                profile = if (showLoading) ScreenState.Loading else profile,
                services = if (showLoading) ScreenState.Loading else services,
                orders = if (showLoading) ScreenState.Loading else orders,
                isRefreshing = refreshing,
                isLoadingMoreOrders = false,
                loadMoreOrdersFailure = null,
                availabilityFailure = null,
                servicesActionFailure = null,
                ordersActionFailure = null,
            )
        }
        viewModelScope.launch {
            when (val result = repository.me()) {
                is ApiResult.Failure -> updateState {
                    copy(profile = ScreenState.Error(result.failure))
                }

                is ApiResult.Success -> {
                    val freelancer = result.data
                    updateState {
                        copy(profile = if (freelancer == null) ScreenState.Empty else ScreenState.Content(freelancer))
                    }
                    if (freelancer != null) {
                        loadServices(freelancer.id)
                        loadOrders()
                    }
                }
            }
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun loadServices(freelancerId: String) {
        viewModelScope.launch {
            val result = repository.services(freelancerId).toListScreenState()
            updateState { copy(services = result) }
        }
    }

    private fun loadOrders() {
        viewModelScope.launch {
            applyOrdersPage(repository.incomingOrders(page = 0))
        }
    }

    private fun applyOrdersPage(result: ApiResult<FreelancerOrderPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(orders = ScreenState.Error(result.failure), hasMoreOrders = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    orders = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMoreOrders = result.data.hasMore,
                )
            }
        }
    }

    private fun loadMoreOrders() {
        val state = currentState
        if (!state.hasMoreOrders || state.isLoadingMoreOrders) return
        val loaded = state.orders as? ScreenState.Content ?: return
        if (loadMoreOrdersJob?.isActive == true) return

        val nextPage = loadedOrdersPage + 1
        updateState { copy(isLoadingMoreOrders = true, loadMoreOrdersFailure = null) }
        loadMoreOrdersJob = viewModelScope.launch {
            when (val result = repository.incomingOrders(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMoreOrders = false, loadMoreOrdersFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedOrdersPage = nextPage
                    updateState {
                        copy(
                            orders = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMoreOrders = result.data.hasMore,
                            isLoadingMoreOrders = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Заказ может приехать на двух соседних страницах, если список изменился
     * между запросами — дедупликация по id, как в
     * [uz.mahalla.feature.freelancer.ui.orders.MyFreelancerOrdersViewModel].
     */
    private fun appended(current: List<FreelancerOrder>, next: List<FreelancerOrder>): List<FreelancerOrder> {
        val known = current.mapTo(mutableSetOf(), FreelancerOrder::id)
        return current + next.filter { known.add(it.id) }
    }

    private fun toggleAvailability() {
        val state = currentState
        if (state.togglingAvailability) return
        val freelancer = (state.profile as? ScreenState.Content)?.data ?: return

        updateState { copy(togglingAvailability = true, availabilityFailure = null) }
        viewModelScope.launch {
            when (val result = repository.toggleAvailability(current = freelancer.isAvailable)) {
                is ApiResult.Failure -> updateState {
                    copy(togglingAvailability = false, availabilityFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        profile = (profile as? ScreenState.Content)?.let {
                            ScreenState.Content(it.data.copy(isAvailable = result.data))
                        } ?: profile,
                        togglingAvailability = false,
                    )
                }
            }
        }
    }

    private fun updateSheetDraft(transform: FreelancerServiceDraft.() -> FreelancerServiceDraft) {
        updateState {
            val sheet = serviceSheet ?: return@updateState this
            val draft = sheet.draft.transform()
            copy(
                serviceSheet = sheet.copy(
                    draft = draft,
                    errors = FreelancerServiceFormValidator.validate(draft),
                    submitError = null,
                ),
            )
        }
    }

    private fun submitServiceSheet() {
        val sheet = currentState.serviceSheet ?: return
        val errors = FreelancerServiceFormValidator.validate(sheet.draft)
        if (errors.isNotEmpty()) {
            updateState { copy(serviceSheet = sheet.copy(errors = errors, validationShown = true)) }
            return
        }
        if (sheet.submitting) return

        updateState { copy(serviceSheet = sheet.copy(submitting = true, submitError = null)) }
        viewModelScope.launch {
            val result = if (sheet.serviceId != null) {
                repository.updateService(sheet.serviceId, sheet.draft)
            } else {
                repository.addService(sheet.draft)
            }
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(serviceSheet = serviceSheet?.copy(submitting = false, submitError = result.failure))
                }

                is ApiResult.Success -> updateState {
                    copy(
                        services = (services as? ScreenState.Content)?.let { content ->
                            ScreenState.Content(content.data.replaceOrAppend(result.data))
                        } ?: ScreenState.Content(listOf(result.data)),
                        serviceSheet = null,
                    )
                }
            }
        }
    }

    private fun List<FreelancerCabinetService>.replaceOrAppend(
        service: FreelancerCabinetService,
    ): List<FreelancerCabinetService> = if (any { it.id == service.id }) {
        map { if (it.id == service.id) service else it }
    } else {
        this + service
    }

    private fun deleteService() {
        val service = currentState.confirmDeleteService ?: return
        if (currentState.pendingServiceId != null) return

        updateState { copy(pendingServiceId = service.id, confirmDeleteService = null, servicesActionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.deleteService(service.id)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingServiceId = null, servicesActionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        services = (services as? ScreenState.Content)?.let { content ->
                            val remaining = content.data.filter { it.id != service.id }
                            if (remaining.isEmpty()) ScreenState.Empty else ScreenState.Content(remaining)
                        } ?: services,
                        pendingServiceId = null,
                    )
                }
            }
        }
    }

    private fun changeOrderStatus(orderId: String, status: FreelancerOrderStatus) {
        if (currentState.pendingOrderId != null) return
        updateState { copy(pendingOrderId = orderId, ordersActionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.updateOrderStatus(orderId, status)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingOrderId = null, ordersActionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        orders = (orders as? ScreenState.Content)?.let { content ->
                            ScreenState.Content(
                                content.data.map { if (it.id == orderId) result.data else it },
                            )
                        } ?: orders,
                        pendingOrderId = null,
                    )
                }
            }
        }
    }
}
