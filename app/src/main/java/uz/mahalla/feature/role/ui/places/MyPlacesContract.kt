package uz.mahalla.feature.role.ui.places

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.role.domain.MyPlace

/**
 * Состояние экрана «Мои заведения» (issue #94).
 *
 * @param pendingPlaceId строка, на которой сейчас идёт запрос доступности:
 * переключатель блокируется точечно, а не всем экраном. Пока он висит,
 * остальные строки тоже не трогаем — ответы приезжали бы на список, которого
 * уже нет (то же правило, что у устройств в профиле, issue #61).
 * @param actionFailure отказ переключателя. Отдельно от [places]: список уже
 * на экране, и прятать его из-за неудавшейся кнопки незачем — причина
 * показывается текстом бэкенда (issue #34).
 * @param loadMoreFailure догрузка страницы не удалась — вместе с причиной,
 * чтобы кнопка «повторить» не осталась без объяснения.
 */
data class MyPlacesState(
    val places: ScreenState<List<MyPlace>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pendingPlaceId: String? = null,
    val actionFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface MyPlacesEvent : UiEvent {
    /**
     * Экран вернулся на передний план: модерация могла принять решение, пока
     * приложение было в фоне, — а увидеть именно это сюда и приходят.
     */
    data object ScreenResumed : MyPlacesEvent

    data object Refreshed : MyPlacesEvent
    data object Retry : MyPlacesEvent
    data object LoadMore : MyPlacesEvent
    data class PlaceClicked(val placeId: String) : MyPlacesEvent
    data class AvailabilityToggled(val placeId: String) : MyPlacesEvent
    data object RegisterPlaceRequested : MyPlacesEvent
}

sealed interface MyPlacesEffect : UiEffect {
    /** Карточка заведения в каталоге — только для того, что прошло модерацию. */
    data class OpenPlace(val placeId: String) : MyPlacesEffect

    /** Пустой список ведёт туда, где заведение регистрируют (issue #84). */
    data object OpenProviderForm : MyPlacesEffect
}
