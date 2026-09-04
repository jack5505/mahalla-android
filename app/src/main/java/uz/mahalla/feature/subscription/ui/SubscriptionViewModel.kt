package uz.mahalla.feature.subscription.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import javax.inject.Inject

/**
 * Подписки (issue #103, эпик #13): тарифы, оформление, пробный период,
 * отмена и автопродление.
 *
 * Ничего про деньги здесь не считается на клиенте: и цена, и срок, и остаток
 * дней приходят с сервера, а после каждого действия состояние подписки берётся
 * из его же ответа. Сложить «активна до» самим значило бы разойтись с тем, что
 * спишется на самом деле.
 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val roleRepository: RoleRepository,
) : MviViewModel<SubscriptionState, SubscriptionEvent, SubscriptionEffect>(SubscriptionState()) {

    init {
        load()
    }

    override fun onEvent(event: SubscriptionEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние. Действие в полёте — тем более: его ответ
            // сам обновит подписку.
            SubscriptionEvent.ScreenResumed ->
                if (!currentState.plans.isLoading && !currentState.isRefreshing &&
                    !currentState.isBusy
                ) {
                    load(showLoading = false)
                }

            SubscriptionEvent.Refreshed -> load(showLoading = false, refreshing = true)
            SubscriptionEvent.Retry -> load()

            // Подписка — отдельная ручка: её повтор не должен дёргать список
            // тарифов, который уже на экране.
            SubscriptionEvent.CurrentRetry -> {
                updateState { copy(current = ScreenState.Loading) }
                viewModelScope.launch { applyCurrent(repository.current()) }
            }

            is SubscriptionEvent.PeriodSelected -> updateState { copy(period = event.period) }

            is SubscriptionEvent.SubscribeClicked -> subscribe(event.planCode)
            is SubscriptionEvent.TrialClicked -> startTrial(event.planCode)

            SubscriptionEvent.CancelRequested -> if (currentState.subscription?.canCancel == true) {
                updateState { copy(confirmCancel = true) }
            }

            SubscriptionEvent.CancelDismissed -> updateState { copy(confirmCancel = false) }
            SubscriptionEvent.CancelConfirmed -> cancel()

            is SubscriptionEvent.AutoRenewToggled -> setAutoRenew(event.enabled)

            SubscriptionEvent.NoticeDismissed -> updateState { copy(notice = null) }
        }
    }

    /**
     * @param showLoading скелетон вместо содержимого. При обновлении поверх
     * уже показанных данных он не нужен: карточка подписки мигала бы на каждом
     * возврате на экран.
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        if (showLoading) {
            updateState { copy(plans = ScreenState.Loading, current = ScreenState.Loading) }
        }
        updateState { copy(isRefreshing = refreshing, actionFailure = null) }
        viewModelScope.launch {
            // Аудитория тарифов зависит от роли: продавцу бэкенд показывает
            // свой набор (`plans?audience=BUSINESS`), и оформляются такие
            // тарифы отдельной ручкой.
            val audience = audience()
            // Две независимые ручки: последовательный запрос удвоил бы время
            // до первого экрана без всякой причины.
            val plans = async { repository.plans(audience) }
            val current = async { repository.current() }
            // Ответ дожидается снаружи `updateState`: тот принимает обычную
            // лямбду, и `await()` внутри неё не компилируется.
            val loadedPlans = plans.await().toListScreenState()
            updateState { copy(plans = loadedPlans) }
            applyCurrent(current.await())
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    /**
     * Роль лежит локально (issue #84) и к правам на сервере отношения не
     * имеет: бэкенд всё равно решает сам. Ошибиться здесь не страшно —
     * покупатель, открывший заведение, просто увидит не тот набор тарифов и
     * поправит роль в профиле.
     */
    private suspend fun audience(): PlanAudience =
        if (roleRepository.current().role == UserRole.Provider) {
            PlanAudience.Business
        } else {
            PlanAudience.User
        }

    private fun applyCurrent(result: ApiResult<Subscription?>) {
        when (result) {
            is ApiResult.Failure -> updateState { copy(current = ScreenState.Error(result.failure)) }
            is ApiResult.Success -> updateState {
                copy(
                    current = result.data
                        ?.let { ScreenState.Content(it) }
                        ?: ScreenState.Empty,
                )
            }
        }
    }

    private fun subscribe(planCode: String) {
        val plan = planOrNull(planCode) ?: return
        if (currentState.isBusy) return

        updateState {
            copy(
                pending = SubscriptionAction.Subscribe(plan.code),
                actionFailure = null,
                notice = null,
            )
        }
        val period = currentState.period
        viewModelScope.launch {
            finish(repository.subscribe(plan, period), SubscriptionNotice.Subscribed)
        }
    }

    private fun startTrial(planCode: String) {
        val plan = planOrNull(planCode) ?: return
        if (currentState.isBusy) return
        // Экран и так не рисует кнопку там, где пробного периода нет; проверка
        // здесь — на случай, если событие всё-таки придёт (устаревший список).
        if (!plan.hasTrial || !currentState.trialAvailable) return

        updateState {
            copy(
                pending = SubscriptionAction.Trial(plan.code),
                actionFailure = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            finish(repository.startTrial(plan), SubscriptionNotice.TrialStarted)
        }
    }

    /**
     * Общий хвост оформления и пробного периода: подписка берётся из ответа
     * сервера, а если он её не назвал — перечитывается. Досчитывать её на
     * клиенте нельзя: срок, статус и грейс-период знает только бэкенд.
     */
    private suspend fun finish(result: ApiResult<Subscription?>, notice: SubscriptionNotice) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(pending = null, actionFailure = result.failure)
            }

            is ApiResult.Success -> {
                val subscription = result.data
                if (subscription != null) {
                    updateState {
                        copy(
                            pending = null,
                            current = ScreenState.Content(subscription),
                            notice = notice,
                        )
                    }
                } else {
                    updateState { copy(pending = null, notice = notice) }
                    applyCurrent(repository.current())
                }
            }
        }
    }

    /**
     * Отмена. После успеха подписка перечитывается, а не правится на месте:
     * бэкенд может оставить доступ до конца оплаченного срока, и «отменено»
     * без даты окончания читалось бы как «доступ пропал сейчас».
     */
    private fun cancel() {
        val subscription = currentState.subscription
        if (subscription?.canCancel != true || currentState.isBusy) {
            updateState { copy(confirmCancel = false) }
            return
        }

        updateState {
            copy(
                pending = SubscriptionAction.Cancel,
                confirmCancel = false,
                actionFailure = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            when (val result = repository.cancel()) {
                is ApiResult.Failure -> updateState {
                    copy(pending = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(pending = null, notice = SubscriptionNotice.Cancelled) }
                    applyCurrent(repository.current())
                }
            }
        }
    }

    /**
     * Автопродление правится на месте: исход запроса — ровно тот флаг, который
     * ушёл на сервер, и перечитывать ради него всю подписку незачем. Отказ
     * возвращает переключатель в прежнее положение и объясняется текстом
     * сервера — молча перекрасить его обратно значило бы соврать.
     */
    private fun setAutoRenew(enabled: Boolean) {
        val subscription = currentState.subscription
        if (subscription?.canToggleAutoRenew != true || currentState.isBusy) return
        if (subscription.autoRenew == enabled) return

        updateState {
            copy(pending = SubscriptionAction.AutoRenew(enabled), actionFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.setAutoRenew(enabled)) {
                is ApiResult.Failure -> updateState {
                    copy(pending = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        pending = null,
                        current = (current as? ScreenState.Content)
                            ?.let { ScreenState.Content(it.data.copy(autoRenew = enabled)) }
                            ?: current,
                    )
                }
            }
        }
    }

    private fun planOrNull(planCode: String): SubscriptionPlan? =
        (currentState.plans as? ScreenState.Content)?.data?.firstOrNull { it.code == planCode }
}
