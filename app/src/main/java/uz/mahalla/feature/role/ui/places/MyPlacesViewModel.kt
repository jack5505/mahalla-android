package uz.mahalla.feature.role.ui.places

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.role.data.ProviderRepository
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.MyPlacePage
import javax.inject.Inject

/**
 * «Мои заведения» (issue #94): что человек зарегистрировал и что с этим
 * решила модерация.
 *
 * Экран открывают ровно за одним — узнать судьбу заявки, — поэтому список
 * перечитывается на каждом возврате: решение модерации приходит без участия
 * приложения, и показанный час назад `PENDING` ничего не стоит.
 */
@HiltViewModel
class MyPlacesViewModel @Inject constructor(
    private val repository: ProviderRepository,
) : MviViewModel<MyPlacesState, MyPlacesEvent, MyPlacesEffect>(MyPlacesState()) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: MyPlacesEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            MyPlacesEvent.ScreenResumed ->
                if (!currentState.places.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            MyPlacesEvent.Refreshed -> load(showLoading = false, refreshing = true)
            MyPlacesEvent.Retry -> load()
            MyPlacesEvent.LoadMore -> loadMore()
            is MyPlacesEvent.PlaceClicked -> open(event.placeId)
            is MyPlacesEvent.AvailabilityToggled -> toggleAvailability(event.placeId)
            MyPlacesEvent.RegisterPlaceRequested ->
                emitEffect(MyPlacesEffect.OpenProviderForm)
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                places = if (showLoading) ScreenState.Loading else places,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myPlaces(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<MyPlacePage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(places = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    places = if (result.data.items.isEmpty()) {
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
     * Догрузка страницы. Провал не стирает уже показанные заведения, но и
     * молча дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу
     * списка больше не сработает — поэтому хвост переходит в состояние
     * «повторить» вместе с причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.places as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.myPlaces(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            places = ScreenState.Content(appended(loaded.data, result.data.items)),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Заведение может приехать на двух соседних страницах, если список
     * изменился между запросами. В `LazyColumn` это дубликат ключа и падение,
     * поэтому дедупликация по id обязательна.
     */
    private fun appended(current: List<MyPlace>, next: List<MyPlace>): List<MyPlace> {
        val known = current.mapTo(mutableSetOf(), MyPlace::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Карточку в каталоге открываем только у того, что модерация пропустила:
     * заявки `PENDING` в каталоге нет, и `GET places/{id}` ответил бы
     * «заведение не найдено». Строка такого заведения и не кликабельна —
     * проверка здесь на случай, если событие всё-таки придёт.
     */
    private fun open(placeId: String) {
        val place = placeOrNull(placeId) ?: return
        if (!place.isOpenable) return
        emitEffect(MyPlacesEffect.OpenPlace(place.id))
    }

    /**
     * «Открыто сейчас». Ручка бэкенда — переключатель, поэтому желаемое
     * состояние не отправляется: известное приложению уходит только затем,
     * чтобы понять исход, если сервер промолчит о новом значении.
     *
     * Список после успеха правится на месте, а не перезапрашивается: сервер
     * уже подтвердил результат, а полная перезагрузка сбросила бы догруженный
     * хвост к первой странице.
     */
    private fun toggleAvailability(placeId: String) {
        val state = currentState
        if (state.pendingPlaceId != null) return
        val place = placeOrNull(placeId) ?: return
        if (!place.canToggleAvailability) return

        updateState { copy(pendingPlaceId = place.id, actionFailure = null) }
        viewModelScope.launch {
            val result = repository.toggleAvailability(
                placeId = place.id,
                current = place.isAvailable,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(pendingPlaceId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        places = (places as? ScreenState.Content)?.let { content ->
                            ScreenState.Content(
                                content.data.map { item ->
                                    if (item.id == place.id) {
                                        item.copy(isAvailable = result.data)
                                    } else {
                                        item
                                    }
                                },
                            )
                        } ?: places,
                        pendingPlaceId = null,
                    )
                }
            }
        }
    }

    private fun placeOrNull(placeId: String): MyPlace? =
        (currentState.places as? ScreenState.Content)?.data?.firstOrNull { it.id == placeId }
}
