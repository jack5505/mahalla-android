package uz.mahalla.feature.gaming.ui.bookings

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.gaming.domain.GamingBooking

/**
 * Состояние экрана «Мои брони» (issue #98).
 *
 * Отмены здесь нет, и это не упущение экрана: ручки отмены брони у бэкенда
 * нет вовсе — ни в `gaming-controller`, ни в общем `orders` (см.
 * `GamingApi`). Кнопка, которую нечем выполнить, хуже её отсутствия, поэтому
 * экран вместо неё объясняет, что бронь снимают в самом заведении.
 *
 * @param loadMoreFailure догрузка страницы не удалась — вместе с причиной,
 * чтобы кнопка «повторить» не осталась без объяснения (issue #53).
 */
data class GamingBookingsState(
    val bookings: ScreenState<List<GamingBooking>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface GamingBookingsEvent : UiEvent {
    /**
     * Экран вернулся на передний план: пока приложение было в фоне, заведение
     * могло закрыть бронь (`bookings/{id}/complete`), и показывать её как
     * предстоящую нельзя.
     */
    data object ScreenResumed : GamingBookingsEvent

    data object Refreshed : GamingBookingsEvent
    data object Retry : GamingBookingsEvent
    data object LoadMore : GamingBookingsEvent
}
