package uz.mahalla.feature.freelancer.ui.profile

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import java.time.LocalDate
import java.time.LocalTime

/**
 * Профиль мастера и заказ услуги (issue #107): кто он → услуга → когда →
 * адрес и комментарий → подтверждение.
 *
 * Шаги живут на одном прокручиваемом экране, а не в мастере из нескольких
 * окон: выбор услуги меняет цену, и ходить за ней назад-вперёд человеку
 * пришлось бы постоянно (то же решение, что в брони, issue #97, и у врачей,
 * issue #99).
 *
 * @param profile и [services] — **отдельные** состояния: это два независимых
 * запроса, и отказ по услугам не должен прятать профиль, который уже приехал.
 * @param times время, которое предлагается выбрать. Это **не** свободные
 * слоты: ручки занятости у фрилансеров нет вовсе (в контроллере её нет —
 * проверено по полной схеме стенда), сетку строит
 * [uz.mahalla.feature.booking.domain.WorkingHours], и экран называет её
 * «удобное время».
 * @param draft черновик заказа целиком. Правила («что ещё не заполнено»,
 * «текст слишком длинный») живут в домене — форму нельзя проверить ни
 * скриншотом, ни запросом.
 * @param orderFailure отказ подтверждения вместе с ответом сервера
 * (issue #34). Набранное при этом остаётся: терять его из-за отказа незачем.
 */
data class FreelancerProfileState(
    val freelancerName: String = "",
    val profile: ScreenState<Freelancer> = ScreenState.Loading,
    val services: ScreenState<List<BarberService>> = ScreenState.Loading,
    val dates: List<LocalDate> = emptyList(),
    val times: List<LocalTime> = emptyList(),
    val draft: FreelancerOrderDraft = FreelancerOrderDraft(),
    val isOrdering: Boolean = false,
    val orderFailure: ApiFailure? = null,
    val ordered: FreelancerOrder? = null,
) : UiState {

    val freelancer: Freelancer? get() = profile.dataOrNull()

    val selectedService: BarberService?
        get() = (services as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == draft.serviceId }

    /**
     * Мастер сам сказал, что заказы сейчас не берёт. Пока профиль не приехал,
     * заказ не запрещаем: последнее слово всё равно за сервером, а выключенная
     * кнопка из-за неудавшегося запроса объясняла бы не то.
     */
    val isUnavailable: Boolean get() = freelancer?.isAvailable == false

    /** Заказывать можно только полностью собранный заказ. */
    val canOrder: Boolean
        get() = draft.canSubmit &&
            selectedService != null &&
            !isUnavailable &&
            !isOrdering &&
            ordered == null
}

sealed interface FreelancerProfileEvent : UiEvent {
    data class ServiceSelected(val serviceId: String) : FreelancerProfileEvent
    data class DateSelected(val date: LocalDate) : FreelancerProfileEvent

    /** `null` — «как можно скорее»: `scheduledAt` тогда не уходит вовсе. */
    data class TimeSelected(val time: LocalTime?) : FreelancerProfileEvent

    data class AddressChanged(val address: String) : FreelancerProfileEvent
    data class CommentChanged(val comment: String) : FreelancerProfileEvent

    data object ProfileRetry : FreelancerProfileEvent
    data object ServicesRetry : FreelancerProfileEvent
    data object OrderClicked : FreelancerProfileEvent
    data object CallClicked : FreelancerProfileEvent

    /** «Мои заказы» — с экрана подтверждения. */
    data object MyOrdersClicked : FreelancerProfileEvent
}

sealed interface FreelancerProfileEffect : UiEffect {
    /** Позвонить мастеру: номер он указал в профиле. */
    data class Dial(val phone: String) : FreelancerProfileEffect

    /**
     * Заказ создан. Экран уходит в «мои заказы»: там он приезжает уже с
     * сервера — вместе со статусом, который мастер может изменить.
     */
    data object OpenMyOrders : FreelancerProfileEffect
}
