package uz.mahalla.feature.freelancer.ui.catalog

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.Freelancer

/**
 * Каталог мастеров (issue #107).
 *
 * @param profession фильтр по специальности — единственный, который приложение
 * отправляет. Город бэкенд тоже принимает, но в каком виде он его ждёт, из
 * контракта не следует (см. `FreelancerApi.freelancers`).
 * @param loadMoreFailure провал догрузки — отдельно от [freelancers]: список
 * уже на экране, и прятать его из-за неудавшегося хвоста незачем (issue #53).
 */
data class FreelancersState(
    val profession: String = "",
    val freelancers: ScreenState<List<Freelancer>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface FreelancersEvent : UiEvent {
    data class ProfessionChanged(val profession: String) : FreelancersEvent

    /** Явная отправка (Enter): ищет сразу, без задержки набора. */
    data object ProfessionSubmitted : FreelancersEvent

    data object Refreshed : FreelancersEvent
    data object Retry : FreelancersEvent
    data object LoadMore : FreelancersEvent

    data class FreelancerClicked(val freelancerId: String) : FreelancersEvent
}

sealed interface FreelancersEffect : UiEffect {
    data class OpenFreelancer(val freelancerId: String, val name: String) : FreelancersEffect
}
