package uz.mahalla.feature.freelancer.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.feature.booking.domain.BookingSlots
import uz.mahalla.feature.booking.domain.WorkingHours
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.navigation.FreelancerRoute
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Профиль мастера и заказ услуги (issue #107).
 *
 * **Сетка времени считается на клиенте** — и это вынужденно: ручки занятости
 * фрилансера у бэкенда нет (в `freelancer-controller` тринадцать путей, слотов
 * среди них нет). Поэтому здесь нет ни запроса за слотами, ни его отмены при
 * перелистывании дней, — но есть пересчёт от текущего момента: сегодняшнее «в
 * девять утра» в полдень предлагать нельзя.
 *
 * Время при этом **необязательно**: в контракте
 * (`CreateOrderRequest.required = [serviceId]`) обязательна одна услуга, а
 * мастера часто вызывают «как можно скорее».
 */
@HiltViewModel
class FreelancerProfileViewModel @Inject constructor(
    private val repository: FreelancerRepository,
    private val roleRepository: RoleRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<FreelancerProfileState, FreelancerProfileEvent, FreelancerProfileEffect>(
    FreelancerProfileState(),
) {

    private val route: FreelancerRoute = savedStateHandle.toRoute()

    init {
        val dates = BookingSlots.dates(clock.instant())
        // День выбран сразу: календарь без выбранного дня не отвечает на
        // вопрос, чьё время показано ниже. Само по себе это ещё не «назначено
        // на время» — назначает выбранный час.
        val today = dates.firstOrNull()
        updateState {
            copy(
                freelancerName = route.freelancerName,
                dates = dates,
                draft = draft.copy(date = today),
                times = today?.let { WorkingHours.times(it, clock.instant()) }.orEmpty(),
            )
        }
        loadProfile()
        loadServices()
        prefillAddress()
    }

    override fun onEvent(event: FreelancerProfileEvent) {
        when (event) {
            is FreelancerProfileEvent.ServiceSelected -> updateState {
                copy(draft = draft.copy(serviceId = event.serviceId), orderFailure = null)
            }

            is FreelancerProfileEvent.DateSelected -> selectDate(event.date)

            is FreelancerProfileEvent.TimeSelected -> updateState {
                copy(draft = draft.copy(time = event.time), orderFailure = null)
            }

            // Отказ снимается на правку: он был про другой текст.
            is FreelancerProfileEvent.AddressChanged -> updateState {
                copy(draft = draft.copy(address = event.address), orderFailure = null)
            }

            is FreelancerProfileEvent.CommentChanged -> updateState {
                copy(draft = draft.copy(comment = event.comment), orderFailure = null)
            }

            FreelancerProfileEvent.ProfileRetry -> loadProfile()
            FreelancerProfileEvent.ServicesRetry -> loadServices()
            FreelancerProfileEvent.OrderClicked -> order()

            FreelancerProfileEvent.CallClicked -> {
                val phone = currentState.freelancer?.phone?.takeIf { it.isNotBlank() } ?: return
                emitEffect(FreelancerProfileEffect.Dial(phone))
            }

            FreelancerProfileEvent.MyOrdersClicked ->
                emitEffect(FreelancerProfileEffect.OpenMyOrders)
        }
    }

    private fun loadProfile() {
        updateState { copy(profile = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.freelancer(route.freelancerId)
            updateState {
                copy(
                    profile = result.toScreenState(),
                    // Имя из маршрута — только заглушка для шапки, пока
                    // профиль едет: у сервера оно точнее.
                    freelancerName = (result as? ApiResult.Success)
                        ?.data
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: freelancerName,
                )
            }
        }
    }

    private fun loadServices() {
        updateState { copy(services = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.services(route.freelancerId)
            updateState { copy(services = result.toListScreenState()) }
            // Единственная услуга выбирается сама: заставлять нажимать на
            // список из одной строки незачем.
            val single = (result as? ApiResult.Success)?.data?.singleOrNull()
            if (single != null) {
                updateState { copy(draft = draft.copy(serviceId = single.id)) }
            }
        }
    }

    /**
     * Адрес, который человек уже назвал в анкете покупателя (issue #84).
     * Подставляется **только в пустое поле**: чтение асинхронное, и затирать
     * набранное нельзя (то же правило, что в оформлении заказа еды).
     */
    private fun prefillAddress() {
        viewModelScope.launch {
            val saved = roleRepository.current().customer.address
            if (saved.isBlank()) return@launch
            updateState {
                if (draft.address.isBlank()) copy(draft = draft.copy(address = saved)) else this
            }
        }
    }

    /**
     * Смена дня пересчитывает сетку от текущего момента и **сбрасывает время**:
     * `10:00` от вчерашнего дня назначило бы заказ не туда, а на сегодня этого
     * времени может уже не быть в списке.
     */
    private fun selectDate(date: LocalDate) {
        if (currentState.draft.date == date) return
        updateState {
            copy(
                draft = draft.copy(date = date, time = null),
                times = WorkingHours.times(date, clock.instant()),
                orderFailure = null,
            )
        }
    }

    /**
     * Заказ.
     *
     * Экран после успеха **не уходит** сам: молчаливый переход читается как
     * «ничего не произошло» (issue #49). Показывается подтверждение с услугой и
     * временем, и уже с него человек идёт в «мои заказы».
     */
    private fun order() {
        val state = currentState
        val service = state.selectedService ?: return
        if (!state.canOrder) return
        val draft = state.draft

        updateState { copy(isOrdering = true, orderFailure = null) }
        viewModelScope.launch {
            val result = repository.order(freelancerId = route.freelancerId, draft = draft)
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isOrdering = false, orderFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        isOrdering = false,
                        ordered = result.data.copy(
                            // Название услуги сервер может и не вернуть, а
                            // подтверждение без него не отвечает на вопрос,
                            // что именно заказали.
                            serviceTitle = result.data.serviceTitle
                                ?: service.title.takeIf { it.isNotBlank() },
                            scheduledAt = result.data.scheduledAt ?: draft.scheduledAt(),
                            address = result.data.address ?: draft.addressOrNull(),
                        ),
                    )
                }
            }
        }
    }
}
