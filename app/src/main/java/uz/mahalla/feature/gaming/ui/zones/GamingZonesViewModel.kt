package uz.mahalla.feature.gaming.ui.zones

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.gaming.data.GamingRepository
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingValidator
import uz.mahalla.feature.gaming.domain.GamingSlots
import uz.mahalla.feature.gaming.domain.GamingZone
import uz.mahalla.navigation.GamingRoute
import java.time.Clock
import javax.inject.Inject

/**
 * Игровые зоны заведения (issue #98): что можно забронировать и во сколько
 * это обойдётся.
 *
 * Расписания зоны и занятых интервалов бэкенд не отдаёт (в контракте их нет
 * вовсе), поэтому слоты считаются на клиенте, а последнее слово о занятости
 * остаётся за сервером: его отказ экран покажет текстом (issue #34).
 */
@HiltViewModel
class GamingZonesViewModel @Inject constructor(
    private val repository: GamingRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<GamingZonesState, GamingZonesEvent, GamingZonesEffect>(GamingZonesState()) {

    private val route: GamingRoute = savedStateHandle.toRoute()

    init {
        updateState { copy(placeName = route.placeName) }
        load()
    }

    override fun onEvent(event: GamingZonesEvent) {
        when (event) {
            GamingZonesEvent.Retry -> load()
            GamingZonesEvent.Refreshed -> load(showLoading = false, refreshing = true)

            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            GamingZonesEvent.ScreenResumed ->
                if (!currentState.zones.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            is GamingZonesEvent.ZoneClicked -> openSheet(event.zoneId)
            GamingZonesEvent.SheetDismissed -> updateState { closedSheet() }
            is GamingZonesEvent.SlotSelected -> updateDraft { copy(startTime = event.startTime) }

            is GamingZonesEvent.DurationChanged -> updateDraft {
                copy(
                    durationHours = event.hours
                        .coerceIn(GamingBookingDraft.MIN_HOURS, GamingBookingDraft.MAX_HOURS),
                )
            }

            GamingZonesEvent.BookClicked -> book()
            GamingZonesEvent.ConfirmationDismissed -> updateState { copy(confirmed = null) }
            GamingZonesEvent.MyBookingsClicked -> emitEffect(GamingZonesEffect.OpenMyBookings)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        updateState {
            copy(
                zones = if (showLoading) ScreenState.Loading else zones,
                isRefreshing = refreshing,
            )
        }
        viewModelScope.launch {
            when (val result = repository.zones(route.placeId)) {
                is ApiResult.Failure -> updateState {
                    copy(zones = ScreenState.Error(result.failure), isRefreshing = false)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        zones = if (result.data.isEmpty()) {
                            ScreenState.Empty
                        } else {
                            ScreenState.Content(result.data)
                        },
                        isRefreshing = false,
                        // Зона, которую человек держал открытой, могла закрыться:
                        // шторка обновляется тем, что приехало.
                        selectedZone = selectedZone?.let { open ->
                            result.data.firstOrNull { it.id == open.id }
                        },
                    )
                }
            }
        }
    }

    /**
     * Шторка открывается только у зоны, которую есть чем забронировать
     * ([GamingZone.isBookable]) — закрытая и без цены и не кликабельна.
     * Слоты считаются здесь, а не при загрузке списка: между открытием экрана
     * и выбором зоны проходит время, и первый слот успел бы устареть.
     */
    private fun openSheet(zoneId: String) {
        val zone = zoneOrNull(zoneId) ?: return
        if (!zone.isBookable) return
        val slots = GamingSlots.next(clock.instant(), DateTimeFormatters.AppZone)
        val draft = GamingBookingDraft(zoneId = zone.id, startTime = slots.firstOrNull())
        updateState {
            copy(
                selectedZone = zone,
                draft = draft,
                slots = slots,
                validationShown = false,
                bookingFailure = null,
                confirmed = null,
            ).revalidated()
        }
    }

    private fun updateDraft(transform: GamingBookingDraft.() -> GamingBookingDraft) {
        val draft = currentState.draft ?: return
        // Правка стирает прошлый отказ: сообщение поверх изменённого выбора
        // относилось бы уже к другой броне.
        updateState { copy(draft = draft.transform(), bookingFailure = null).revalidated() }
    }

    /** Проверка идёт от одного «сейчас»: и время, и часы (правило issue #9). */
    private fun GamingZonesState.revalidated(): GamingZonesState {
        val draft = draft ?: return copy(errors = emptyList())
        return copy(errors = GamingBookingValidator.validate(draft, clock.instant()))
    }

    private fun book() {
        val state = currentState.revalidated()
        val zone = state.selectedZone ?: return
        val draft = state.draft ?: return
        if (state.isBooking) return
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }

        updateState { state.copy(isBooking = true, bookingFailure = null) }
        viewModelScope.launch {
            when (val result = repository.book(draft, zoneName = zone.name)) {
                is ApiResult.Failure -> updateState {
                    copy(isBooking = false, bookingFailure = result.failure)
                }

                // Шторка закрывается, подтверждение остаётся на экране: бронь
                // состоялась, и об этом надо сказать словами, а не пустотой.
                is ApiResult.Success -> updateState {
                    closedSheet().copy(isBooking = false, confirmed = result.data)
                }
            }
        }
    }

    private fun GamingZonesState.closedSheet(): GamingZonesState = copy(
        selectedZone = null,
        draft = null,
        slots = emptyList(),
        errors = emptyList(),
        validationShown = false,
        bookingFailure = null,
    )

    private fun zoneOrNull(zoneId: String): GamingZone? =
        (currentState.zones as? ScreenState.Content)?.data?.firstOrNull { it.id == zoneId }
}
