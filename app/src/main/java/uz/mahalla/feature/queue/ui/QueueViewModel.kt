package uz.mahalla.feature.queue.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.queue.data.WalkInRepository
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInRequestValidator
import uz.mahalla.navigation.QueueRoute
import java.time.Clock
import javax.inject.Inject

/**
 * Очередь заведения (issue #96): взять талон и отменить его.
 *
 * **Опроса статуса здесь нет, и это не упущение.** Прочитать состояние своего
 * талона у бэкенда нечем: в walk-in-контроллере семь путей, и ни один из них
 * не отдаёт талон по id или список своих (см. `WalkInApi`). Поэтому экран
 * показывает последнее известное состояние вместе с временем, на которое оно
 * известно, а числа очереди прячет, когда они устарели
 * (`WalkInTicket.showsQueueInfo`). Когда ручка чтения появится, опрос
 * добавляется сюда по образцу `OrderStatusViewModel` — раз в пять секунд с
 * остановкой на финальном статусе и на `ScreenStopped`.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repository: WalkInRepository,
    private val profileStore: UserProfileStore,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<QueueState, QueueEvent, QueueEffect>(QueueState()) {

    private val route: QueueRoute = savedStateHandle.toRoute()

    init {
        updateState {
            copy(placeName = route.placeName, request = WalkInRequest(placeId = route.placeId))
        }
        viewModelScope.launch {
            // Имя из профиля — то же, что показывает шапка (issue #61):
            // набирать заново уже известное приложению — самый быстрый способ
            // получить брошенную форму. Профиль пуст — поле остаётся пустым.
            val name = profileStore.current().fullName.orEmpty()
            val ticket = repository.activeTicket(route.placeId)
            updateState {
                copy(
                    isLoading = false,
                    ticket = ticket,
                    queueInfoIsCurrent = ticket?.showsQueueInfo(clock.instant()) ?: false,
                    request = if (request.userName.isBlank()) {
                        request.copy(userName = name)
                    } else {
                        request
                    },
                ).revalidated()
            }
        }
    }

    override fun onEvent(event: QueueEvent) {
        when (event) {
            is QueueEvent.NameChanged -> updateRequest { copy(userName = event.name) }
            is QueueEvent.ServiceChanged -> updateRequest { copy(serviceName = event.service) }
            QueueEvent.SubmitClicked -> submit()

            QueueEvent.CancelClicked -> updateState {
                copy(cancelConfirmVisible = true, cancelFailure = null)
            }

            QueueEvent.CancelDismissed -> updateState { copy(cancelConfirmVisible = false) }
            QueueEvent.CancelConfirmed -> cancel()

            // Пересчёт свежести, а не запрос: обновлять состояние талона нечем.
            QueueEvent.ScreenResumed -> updateState {
                copy(queueInfoIsCurrent = ticket?.showsQueueInfo(clock.instant()) ?: false)
            }

            QueueEvent.NotificationsClicked -> emitEffect(QueueEffect.OpenNotifications)
            QueueEvent.BackClicked -> emitEffect(QueueEffect.NavigateBack)
        }
    }

    private fun updateRequest(transform: WalkInRequest.() -> WalkInRequest) {
        // Правка стирает прошлый отказ: сообщение поверх изменённой формы
        // относилось бы уже к другому запросу.
        updateState { copy(request = request.transform(), submitFailure = null).revalidated() }
    }

    private fun QueueState.revalidated(): QueueState =
        copy(errors = WalkInRequestValidator.validate(request))

    private fun submit() {
        val state = currentState.revalidated()
        if (state.ticket != null || state.isSubmitting) return
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }

        updateState { state.copy(isSubmitting = true, submitFailure = null) }
        viewModelScope.launch {
            val result = repository.take(state.request, placeName = state.placeName)
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isSubmitting = false, submitFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        isSubmitting = false,
                        ticket = result.data,
                        // Только что с сервера — числа очереди свежие по
                        // определению.
                        queueInfoIsCurrent = result.data.showsQueueInfo(clock.instant()),
                    )
                }
            }
        }
    }

    private fun cancel() {
        val ticket = currentState.ticket ?: return
        if (!currentState.canCancel || currentState.isCancelling) return

        updateState { copy(isCancelling = true, cancelConfirmVisible = false, cancelFailure = null) }
        viewModelScope.launch {
            when (val result = repository.cancel(ticket)) {
                is ApiResult.Failure -> updateState {
                    copy(isCancelling = false, cancelFailure = result.failure)
                }

                // Талон остаётся на экране с новым состоянием: человек должен
                // увидеть, что отмена состоялась, а не оказаться на пустой
                // форме, будто записи и не было.
                is ApiResult.Success -> updateState {
                    copy(
                        isCancelling = false,
                        ticket = result.data,
                        queueInfoIsCurrent = false,
                    )
                }
            }
        }
    }
}
