package uz.mahalla.feature.freelancer.ui.catalog

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerPage
import javax.inject.Inject

/**
 * Каталог мастеров (issue #107): список с фильтром по специальности и
 * догрузкой страниц.
 *
 * Фильтр уходит с задержкой [SEARCH_DEBOUNCE_MS] — как в поиске по каталогу
 * заведений (эпик 4.3): без неё каждая буква становится отдельным сетевым
 * вызовом, ответы приходят вразнобой и список моргает.
 */
@HiltViewModel
class FreelancersViewModel @Inject constructor(
    private val repository: FreelancerRepository,
) : MviViewModel<FreelancersState, FreelancersEvent, FreelancersEffect>(FreelancersState()) {

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: FreelancersEvent) {
        when (event) {
            is FreelancersEvent.ProfessionChanged -> {
                updateState { copy(profession = event.profession) }
                load(delayMillis = SEARCH_DEBOUNCE_MS)
            }

            // Осознанное действие — ждать его незачем.
            FreelancersEvent.ProfessionSubmitted -> load()
            FreelancersEvent.Refreshed -> load(showLoading = false, refreshing = true)
            FreelancersEvent.Retry -> load()
            FreelancersEvent.LoadMore -> loadMore()

            is FreelancersEvent.FreelancerClicked -> {
                val freelancer = freelancersOrEmpty().firstOrNull { it.id == event.freelancerId }
                    ?: return
                emitEffect(
                    FreelancersEffect.OpenFreelancer(
                        freelancerId = freelancer.id,
                        name = freelancer.name,
                    ),
                )
            }
        }
    }

    private fun load(
        showLoading: Boolean = true,
        refreshing: Boolean = false,
        delayMillis: Long = 0,
    ) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        loadedPage = 0
        val profession = currentState.profession
        searchJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            updateState {
                copy(
                    freelancers = if (showLoading) ScreenState.Loading else freelancers,
                    isRefreshing = refreshing,
                    isLoadingMore = false,
                    loadMoreFailure = null,
                )
            }
            applyPage(repository.freelancers(profession = profession, page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<FreelancerPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(freelancers = ScreenState.Error(result.failure), hasMore = false)
            }

            // Пустая выдача — не ошибка: каталог мастеров на стенде сегодня
            // пуст, и это ответ сервера, а не поломка экрана (issue #53).
            is ApiResult.Success -> updateState {
                copy(
                    freelancers = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Догрузка страницы. Провал не стирает уже показанных мастеров, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу больше
     * не сработает — поэтому хвост переходит в состояние «повторить» вместе с
     * причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.freelancers as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.freelancers(
                profession = currentState.profession,
                page = nextPage,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            freelancers = ScreenState.Content(
                                appended(loaded.data, result.data.items),
                            ),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Мастер может приехать на двух соседних страницах, если каталог
     * изменился между запросами. В `LazyColumn` это дубликат ключа и падение,
     * поэтому дедупликация по id обязательна.
     */
    private fun appended(current: List<Freelancer>, next: List<Freelancer>): List<Freelancer> {
        val known = current.mapTo(mutableSetOf(), Freelancer::id)
        return current + next.filter { known.add(it.id) }
    }

    private fun freelancersOrEmpty(): List<Freelancer> =
        (currentState.freelancers as? ScreenState.Content)?.data.orEmpty()

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
