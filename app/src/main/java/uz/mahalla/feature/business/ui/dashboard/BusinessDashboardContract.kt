package uz.mahalla.feature.business.ui.dashboard

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessSection

/**
 * Состояние бизнес-панели (задача 12.1).
 *
 * Доступ и метрики — **два разных состояния экрана**, и это главное здесь.
 * Права приезжают из `places/my`, метрики — из аналитики, и отказ второй ручки
 * не должен выглядеть как «у вас нет доступа»: панель с разделами остаётся на
 * месте, а вместо чисел стоит причина с кнопкой «повторить».
 *
 * @param pauseInProgress идёт запрос «открыто/закрыто». Переключатель на это
 * время занят: второй тап завёл бы второй переворот флага, и результат зависел
 * бы от порядка ответов (то же правило, что в «моих заведениях», issue #94).
 */
data class BusinessDashboardState(
    val placeName: String = "",
    val access: ScreenState<BusinessAccess> = ScreenState.Loading,
    val metrics: ScreenState<BusinessDashboard> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val pauseInProgress: Boolean = false,
    val actionFailure: ApiFailure? = null,
) : UiState {
    /** Заголовок: имя из маршрута до загрузки, из ответа сервера — после. */
    val title: String
        get() = (access as? ScreenState.Content)?.data?.placeName
            ?.takeIf(String::isNotBlank)
            ?: placeName
}

sealed interface BusinessDashboardEvent : UiEvent {
    /**
     * Экран вернулся на передний план. Метрики дня, очередь и заказы меняются
     * без участия приложения — показанное десять минут назад число заказов
     * ничего не стоит.
     */
    data object ScreenResumed : BusinessDashboardEvent

    data object Refreshed : BusinessDashboardEvent
    data object Retry : BusinessDashboardEvent

    /** Повтор только метрик: доступ уже подтверждён, перезапрашивать нечего. */
    data object RetryMetrics : BusinessDashboardEvent

    data class SectionClicked(val section: BusinessSection) : BusinessDashboardEvent

    /** «Пауза»: заведение временно не принимает. */
    data object PauseToggled : BusinessDashboardEvent
}

sealed interface BusinessDashboardEffect : UiEffect {
    data class OpenQueue(val placeId: String, val placeName: String) : BusinessDashboardEffect
    data class OpenOrders(val placeId: String, val placeName: String) : BusinessDashboardEffect
    data class OpenMenu(val placeId: String, val placeName: String) : BusinessDashboardEffect
}

/** Куда ведёт раздел. Отдельно от [BusinessSection] — домен про экраны не знает. */
internal fun BusinessSection.effect(access: BusinessAccess): BusinessDashboardEffect = when (this) {
    BusinessSection.Queue -> BusinessDashboardEffect.OpenQueue(access.placeId, access.placeName)
    BusinessSection.Orders -> BusinessDashboardEffect.OpenOrders(access.placeId, access.placeName)
    BusinessSection.Menu -> BusinessDashboardEffect.OpenMenu(access.placeId, access.placeName)
}
