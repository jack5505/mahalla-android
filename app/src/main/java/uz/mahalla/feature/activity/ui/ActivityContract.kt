package uz.mahalla.feature.activity.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFilter
import uz.mahalla.feature.activity.domain.ActivityMerge
import uz.mahalla.feature.activity.domain.ActivitySource

/**
 * Состояние «моих активностей» (issue #73, задача T7).
 *
 * @param items активности **всех** источников и **обеих** вкладок: фильтр
 * переключается на клиенте, иначе каждый тап по «истории» стоил бы пяти
 * запросов. Отбор по вкладке делает [visible].
 *
 * `ScreenState.Error` здесь означает только **полный** отказ — не ответил ни
 * один источник (истёкшая сессия, нет сети). Частичный отказ живёт в
 * [sourceFailures] и список не прячет: это прямое требование T7.
 *
 * @param sourceFailures источники, не ответившие на последнюю загрузку —
 * вместе с ответом сервера, чтобы причину было видно его текстом (issue #34).
 * @param loadMoreFailure догрузка не удалась; вместе с причиной, чтобы кнопка
 * «повторить» не осталась без объяснения (issue #53).
 * @param nextPages курсор догрузки: у какого источника какая страница
 * следующая. Пусто — догружать нечего.
 */
data class ActivityState(
    val items: ScreenState<List<Activity>> = ScreenState.Loading,
    val filter: ActivityFilter = ActivityFilter.Active,
    val sourceFailures: Map<ActivitySource, ApiFailure> = emptyMap(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val nextPages: Map<ActivitySource, Int> = emptyMap(),
) : UiState {

    val hasMore: Boolean get() = nextPages.isNotEmpty()

    /**
     * Строки текущей вкладки. Порядок и отбор считает домен, а не композабл:
     * иначе правило «ближайшее сверху» было бы нечем проверить тестом.
     *
     * Пустой список здесь при непустом [items] — это «пусто в этой вкладке», а
     * не «вы ещё ничего не заказывали»: у экрана под эти два случая разные
     * пустые состояния, и предлагать «сходите на главную» тому, у кого
     * двадцать заказов в истории, значит не заметить его самого.
     */
    val visible: List<Activity> get() = ActivityMerge.filter(items.dataOrNull().orEmpty(), filter)
}

sealed interface ActivityEvent : UiEvent {
    /**
     * Экран вернулся на передний план. Статус заказа меняется на сервере, и
     * этот таб открывают как раз чтобы его увидеть.
     */
    data object ScreenResumed : ActivityEvent

    data object Refreshed : ActivityEvent
    data object Retry : ActivityEvent
    data object LoadMore : ActivityEvent
    data class FilterSelected(val filter: ActivityFilter) : ActivityEvent
    data class ActivityClicked(val key: String) : ActivityEvent

    /** Кнопка пустого состояния: «ещё ничего не заказывали» → на главную. */
    data object DiscoveryRequested : ActivityEvent
}

sealed interface ActivityEffect : UiEffect {
    /** Статус заказа «Еды» — единственный экран, который даёт контракт. */
    data class OpenFoodOrder(val orderId: String) : ActivityEffect

    data object OpenDiscovery : ActivityEffect
}
