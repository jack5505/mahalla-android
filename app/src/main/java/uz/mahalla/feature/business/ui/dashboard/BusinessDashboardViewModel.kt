package uz.mahalla.feature.business.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessSection
import uz.mahalla.navigation.BusinessArgs
import javax.inject.Inject

/**
 * Бизнес-панель заведения (задача 12.1): метрики дня и вход в разделы.
 *
 * Порядок запросов важен: **сначала доступ, потом всё остальное**. Аналитику
 * чужого заведения бэкенд не отдаст, но и спрашивать её незачем — если
 * заведения нет среди «моих», экран должен сказать это словами, а не показать
 * 403 от ручки метрик.
 */
@HiltViewModel
class BusinessDashboardViewModel @Inject constructor(
    private val repository: BusinessRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessDashboardState, BusinessDashboardEvent, BusinessDashboardEffect>(
    BusinessDashboardState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
    ),
) {

    /**
     * Читается из `SavedStateHandle` по имени, а не через `toRoute()`: тот
     * разбирает маршрут настоящим `Bundle`, а в JVM-тестах android.jar
     * заглушен и все аргументы молча приходят пустыми (то же решение, что у
     * `MyAppointmentsArgs`).
     */
    private val placeId: String = savedStateHandle.get<String>(BusinessArgs.PLACE_ID).orEmpty()

    /**
     * Текущая загрузка. Хранится, чтобы «повторить» поверх pull-to-refresh не
     * давало двух параллельных запросов: выигрывал бы ответивший последним, то
     * есть возможен откат к более старым данным (нашло ревью).
     */
    private var loadJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: BusinessDashboardEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            BusinessDashboardEvent.ScreenResumed ->
                if (!currentState.access.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            BusinessDashboardEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessDashboardEvent.Retry -> load()
            BusinessDashboardEvent.RetryMetrics -> retryMetrics()
            is BusinessDashboardEvent.SectionClicked -> open(event.section)
            BusinessDashboardEvent.PauseToggled -> togglePause()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        updateState {
            copy(
                access = if (showLoading) ScreenState.Loading else access,
                metrics = if (showLoading) ScreenState.Loading else metrics,
                isRefreshing = refreshing,
                actionFailure = null,
            )
        }
        loadJob = viewModelScope.launch {
            when (val result = repository.access(placeId)) {
                is ApiResult.Failure -> {
                    // «Это не ваше заведение» — не сбой, а ответ по существу:
                    // `ApiError.Business` без текста сервера показался бы общим
                    // «что-то пошло не так» с кнопкой «повторить», которая
                    // гарантированно перелистает `places/my` и снова откажет
                    // (нашло ревью). Поэтому у него своё пустое состояние с
                    // объяснением, а retry остаётся у настоящих сбоев.
                    val noAccess = result.error ==
                        ApiError.Business(BusinessRepository.NO_ACCESS_CODE)
                    val state = if (noAccess) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Error(result.failure)
                    }
                    updateState {
                        copy(
                            access = state,
                            // Метрики без доступа не грузятся вовсе — оставлять
                            // их в «загрузке» значит вечный скелетон под
                            // объяснением.
                            metrics = state,
                            isRefreshing = false,
                        )
                    }
                }

                is ApiResult.Success -> {
                    updateState { copy(access = ScreenState.Content(result.data)) }
                    fetchMetrics()
                    updateState { copy(isRefreshing = false) }
                }
            }
        }
    }

    private fun retryMetrics() {
        if (currentState.metrics.isLoading) return
        updateState { copy(metrics = ScreenState.Loading) }
        viewModelScope.launch { fetchMetrics() }
    }

    /**
     * Метрики отдельно от доступа: их отказ не прячет разделы.
     *
     * `suspend`, а не своя корутина: pull-to-refresh должен погаснуть тогда,
     * когда доехали и права, и числа, — иначе индикатор снимается на пустом
     * экране, и обновление выглядит неудавшимся.
     *
     * Пустой словарь — это [ScreenState.Empty], а не пустой контент: у нового
     * заведения метрик ещё нет, и «ничего за сегодня» надо сказать словами.
     */
    private suspend fun fetchMetrics() {
        val state = when (val result = repository.dashboard(placeId)) {
            is ApiResult.Failure -> ScreenState.Error(result.failure)
            is ApiResult.Success ->
                if (result.data.isEmpty) ScreenState.Empty else ScreenState.Content(result.data)
        }
        updateState { copy(metrics = state) }
    }

    /**
     * Раздел открывается только тогда, когда он и существует у заведения, и
     * разрешён роли. Проверка здесь — страховка: экран такую карточку и не
     * рисует, но событие может прийти из превью или из будущей клавиатурной
     * навигации.
     */
    private fun open(section: BusinessSection) {
        val access = accessOrNull() ?: return
        if (!access.canOpen(section)) return
        emitEffect(section.effect(access))
    }

    /**
     * «Пауза». Ручка бэкенда — переключатель, поэтому желаемое состояние не
     * отправляется: известное приложению уходит только затем, чтобы понять
     * исход, если сервер промолчит о новом значении.
     */
    private fun togglePause() {
        val access = accessOrNull() ?: return
        if (!access.canPause || currentState.pauseInProgress) return

        updateState { copy(pauseInProgress = true, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.togglePause(access.placeId, access.isAvailable)) {
                is ApiResult.Failure -> updateState {
                    copy(pauseInProgress = false, actionFailure = result.failure)
                }

                // Права перечитываются заново: пока шёл запрос, `load()` мог
                // привезти свежее заведение, и снимок, взятый до нажатия,
                // откатил бы вместе с флагом и роль, и статус модерации
                // (нашло ревью).
                is ApiResult.Success -> updateState {
                    copy(
                        access = (this.access as? ScreenState.Content)
                            ?.let { ScreenState.Content(it.data.copy(isAvailable = result.data)) }
                            ?: this.access,
                        pauseInProgress = false,
                    )
                }
            }
        }
    }

    private fun accessOrNull(): BusinessAccess? =
        (currentState.access as? ScreenState.Content)?.data
}
