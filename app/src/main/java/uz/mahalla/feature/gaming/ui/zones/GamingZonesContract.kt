package uz.mahalla.feature.gaming.ui.zones

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingError
import uz.mahalla.feature.gaming.domain.GamingZone
import java.time.Instant

/**
 * Состояние экрана игровых зон (issue #98): список зон заведения и шторка
 * брони поверх него.
 *
 * @param selectedZone открытая шторка. Зона держится целиком, а не одним id:
 * шторке нужны имя и цена, а список под ней может успеть перезагрузиться.
 * @param slots время начала. Считается на клиенте (расписания зоны бэкенд не
 * отдаёт) и пересчитывается при каждом открытии шторки: слоты, посчитанные
 * час назад, уже в прошлом.
 * @param validationShown причины показываются только после первой попытки
 * отправки: подсвечивать невыбранное время сразу — ругать за то, что человек
 * ещё не начал.
 * @param bookingFailure отказ брони вместе с ответом сервера (issue #34).
 * Живёт **в шторке**: закрыть её значило бы потерять и объяснение, и выбор.
 * @param confirmed подтверждённая бронь. Шторка при этом закрывается, а на
 * экране остаётся плашка — иначе человек не понял бы, состоялась бронь или
 * нет.
 */
data class GamingZonesState(
    val placeName: String = "",
    val zones: ScreenState<List<GamingZone>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val selectedZone: GamingZone? = null,
    val draft: GamingBookingDraft? = null,
    val slots: List<Instant> = emptyList(),
    val errors: List<GamingBookingError> = emptyList(),
    val validationShown: Boolean = false,
    val isBooking: Boolean = false,
    val bookingFailure: ApiFailure? = null,
    val confirmed: GamingBooking? = null,
) : UiState {

    val canBook: Boolean get() = errors.isEmpty() && !isBooking

    /** Сумма к оплате: цена часа выбранной зоны × выбранные часы. */
    val totalPrice: Long
        get() = selectedZone?.totalPrice(draft?.durationHours ?: 0) ?: 0
}

sealed interface GamingZonesEvent : UiEvent {
    data object Retry : GamingZonesEvent
    data object Refreshed : GamingZonesEvent

    /**
     * Экран вернулся на передний план: зону могли занять, пока приложение было
     * в фоне, — а бронировать занятое незачем.
     */
    data object ScreenResumed : GamingZonesEvent

    data class ZoneClicked(val zoneId: String) : GamingZonesEvent
    data object SheetDismissed : GamingZonesEvent
    data class SlotSelected(val startTime: Instant) : GamingZonesEvent
    data class DurationChanged(val hours: Int) : GamingZonesEvent
    data object BookClicked : GamingZonesEvent

    /** Плашка подтверждения снимается — она про уже случившееся. */
    data object ConfirmationDismissed : GamingZonesEvent

    data object MyBookingsClicked : GamingZonesEvent
}

sealed interface GamingZonesEffect : UiEffect {
    data object OpenMyBookings : GamingZonesEffect
}
