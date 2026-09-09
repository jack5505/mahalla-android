package uz.mahalla.feature.business.ui.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessMenuItem
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.NewMenuItemValidator
import uz.mahalla.navigation.BusinessArgs
import javax.inject.Inject

/**
 * Меню и стоп-лист (задача 12.4).
 *
 * **Расписания здесь нет, и это не пропуск.** В задаче эпика оно значится, но
 * у бэкенда нет ни одной ручки с рабочими часами: `UpdateRequest` заведения
 * принимает имя, описание, адрес, координаты, город, телефон и сайт — и всё
 * (сверено по `/v3/api-docs` 2026-09-09). Единственный признак работы
 * заведения — «открыто сейчас», он же «пауза» на дашборде. Экран расписания
 * без ручки обещал бы кухне часы, о которых сервер не узнает; расхождение
 * заведено отдельным issue.
 */
@HiltViewModel
class BusinessMenuViewModel @Inject constructor(
    private val repository: BusinessRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessMenuState, BusinessMenuEvent, BusinessMenuEffect>(
    BusinessMenuState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
    ),
) {

    private val placeId: String = savedStateHandle.get<String>(BusinessArgs.PLACE_ID).orEmpty()

    /**
     * Текущая загрузка. Хранится, чтобы «повторить» поверх pull-to-refresh не
     * давало двух параллельных запросов: выигрывал бы ответивший последним, то
     * есть возможен откат к более старому меню (нашло ревью).
     */
    private var loadJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: BusinessMenuEvent) {
        when (event) {
            BusinessMenuEvent.ScreenResumed ->
                if (!currentState.menu.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            BusinessMenuEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessMenuEvent.Retry -> load()
            is BusinessMenuEvent.StopListToggled -> toggleStopList(event.itemId)

            BusinessMenuEvent.AddItemClicked -> openForm()
            BusinessMenuEvent.FormDismissed -> closeForm()
            is BusinessMenuEvent.SectionSelected -> editForm { copy(sectionId = event.sectionId) }
            is BusinessMenuEvent.NameChanged -> editForm { copy(name = event.value) }
            // Цена — только цифры: `-` и `,` бэкенд всё равно не примет
            // (`price` — целое), а молча съеденный символ в поле ввода читается
            // как сломанная клавиатура. Поэтому фильтруем на входе, а не после.
            is BusinessMenuEvent.PriceChanged ->
                editForm { copy(priceText = event.value.filter(Char::isDigit)) }

            is BusinessMenuEvent.DescriptionChanged -> editForm { copy(description = event.value) }
            is BusinessMenuEvent.PrepMinutesChanged ->
                editForm { copy(prepMinutesText = event.value.filter(Char::isDigit)) }

            is BusinessMenuEvent.HalalChanged -> editForm { copy(isHalal = event.value) }
            BusinessMenuEvent.SaveClicked -> save()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        updateState {
            copy(
                menu = if (showLoading) ScreenState.Loading else menu,
                isRefreshing = refreshing,
                actionFailure = null,
            )
        }
        loadJob = viewModelScope.launch {
            applyMenu(repository.menu(placeId))
            updateState { copy(isRefreshing = false) }
        }
    }

    /**
     * Пусто — это **нет разделов**, а не «нет блюд».
     *
     * Разница принципиальная: `CreateItemRequest` требует `menuId`, то есть
     * добавить позицию можно только в существующий раздел. Заведение с
     * заведёнными разделами и пустой кухней — самый частый первый день, и
     * состояние `Empty` тогда прятало бы разделы вместе с единственной кнопкой,
     * ради которой на экран и пришли (нашло ревью).
     */
    private fun applyMenu(result: ApiResult<BusinessMenu>) {
        updateState { copy(menu = result.toMenuState()) }
    }

    /**
     * Стоп-лист. Ручка ничего не возвращает (`ApiResponseVoid`), поэтому новое
     * состояние выводится из известного — и правится на месте, а не
     * перезагрузкой: меню длинное, и скролл к нужной позиции терять нельзя.
     */
    private fun toggleStopList(itemId: String) {
        val state = currentState
        if (state.isBusy) return
        val item = itemOrNull(itemId) ?: return

        updateState { copy(pendingItemId = itemId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.toggleStopList(itemId, item.isAvailable)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingItemId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            menu = replaced(itemId, result.data),
                            pendingItemId = null,
                        )
                    }
                    emitEffect(
                        BusinessMenuEffect.StopListChanged(
                            name = item.name,
                            stopped = !result.data,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Раздел подставляется первым из имеющихся: у заведения он чаще всего один,
     * и заставлять выбирать из списка в одну строку незачем. Список из
     * нескольких экран всё равно покажет.
     */
    private fun openForm() {
        val sections = currentState.sectionIds
        updateState {
            copy(
                isFormVisible = true,
                form = NewMenuItemForm(sectionId = sections.firstOrNull().orEmpty()),
                formErrors = emptyList(),
                formFailure = null,
            )
        }
    }

    /**
     * Закрытие стирает форму: вернуться и дописать блюдо, начатое полчаса
     * назад, — не тот сценарий, ради которого стоит хранить черновик, а
     * всплывшая при следующем открытии чужая цена опаснее пустого поля.
     */
    private fun closeForm() {
        if (currentState.isSaving) return
        updateState {
            copy(
                isFormVisible = false,
                form = NewMenuItemForm(),
                formErrors = emptyList(),
                formFailure = null,
            )
        }
    }

    /**
     * Правка поля пересчитывает замечания только тогда, когда они уже
     * показаны: до первой попытки сохранить форма молчит, после — исправляется
     * на глазах.
     */
    private fun editForm(edit: NewMenuItemForm.() -> NewMenuItemForm) {
        updateState {
            val next = form.edit()
            copy(
                form = next,
                formErrors = if (formErrors.isEmpty()) {
                    emptyList()
                } else {
                    NewMenuItemValidator.validate(next)
                },
                formFailure = null,
            )
        }
    }

    private fun save() {
        val state = currentState
        if (state.isSaving) return
        val form = state.form.trimmed()
        val errors = NewMenuItemValidator.validate(form)
        if (errors.isNotEmpty()) {
            updateState { copy(form = form, formErrors = errors) }
            return
        }

        updateState { copy(isSaving = true, formErrors = emptyList(), formFailure = null) }
        viewModelScope.launch {
            when (val result = repository.createItem(placeId, form)) {
                is ApiResult.Failure -> updateState {
                    copy(isSaving = false, formFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            menu = result.toMenuState(),
                            isSaving = false,
                            isFormVisible = false,
                            form = NewMenuItemForm(),
                        )
                    }
                    emitEffect(BusinessMenuEffect.ItemCreated(form.name))
                }
            }
        }
    }

    private fun replaced(itemId: String, isAvailable: Boolean): ScreenState<BusinessMenu> {
        val content = currentState.menu as? ScreenState.Content ?: return currentState.menu
        return ScreenState.Content(
            BusinessMenu(
                sections = content.data.sections.map { section ->
                    section.copy(
                        items = section.items.map { item ->
                            if (item.id == itemId) item.copy(isAvailable = isAvailable) else item
                        },
                    )
                },
            ),
        )
    }

    private fun itemOrNull(itemId: String): BusinessMenuItem? =
        (currentState.menu as? ScreenState.Content)?.data?.item(itemId)

    private fun ApiResult<BusinessMenu>.toMenuState(): ScreenState<BusinessMenu> = when (this) {
        is ApiResult.Failure -> ScreenState.Error(failure)
        is ApiResult.Success ->
            if (data.sections.isEmpty()) ScreenState.Empty else ScreenState.Content(data)
    }
}
