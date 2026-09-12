package uz.mahalla.feature.subscription.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionCharge
import uz.mahalla.feature.subscription.domain.SubscriptionChargePage
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.canRenew
import java.time.Clock
import javax.inject.Inject

/**
 * Подписки (issue #103, эпик #13): тарифы, оформление, пробный период,
 * продление, отмена, автопродление и история списаний.
 *
 * Ничего про деньги здесь не считается на клиенте: и цена, и срок, и остаток
 * дней приходят с сервера, а после каждого действия состояние подписки берётся
 * из его же ответа. Сложить «активна до» самим значило бы разойтись с тем, что
 * спишется на самом деле. Единственный свой расчёт — прогноз «продление
 * доведёт до» ([uz.mahalla.feature.subscription.domain.SubscriptionRenewal]):
 * до самого запроса этой даты не существует нигде, и она так и подписана —
 * прогнозом.
 */
@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val roleRepository: RoleRepository,
    private val clock: Clock,
) : MviViewModel<SubscriptionState, SubscriptionEvent, SubscriptionEffect>(SubscriptionState()) {

    /** Страница платежей, с которой продолжать историю списаний. */
    private var nextChargesPage = 0

    /**
     * Полная загрузка экрана. Хранится, потому что по состоянию её не видно:
     * обновление поверх показанных данных не ставит ни скелетон, ни
     * `isRefreshing`, — а два таких `load()` подряд (два `ON_RESUME`, возврат
     * из системного диалога) дали бы по два запроса в каждую ручку и
     * состояние от того, кто ответил последним.
     */
    private var loadJob: Job? = null

    /** Загрузка одной истории списаний — повтором или после действия. */
    private var chargesJob: Job? = null
    private var chargesLoadMoreJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: SubscriptionEvent) {
        when (event) {
            // Защита от дубля (первый resume, два resume подряд) — общая, см.
            // MviViewModel.onScreenResumed (issue #145, #209). Действие в
            // полёте — тем более повод не перезапрашивать: его ответ сам
            // обновит подписку.
            SubscriptionEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true || currentState.isBusy },
                load = { load(showLoading = false) },
            )

            SubscriptionEvent.Refreshed -> load(showLoading = false, refreshing = true)
            SubscriptionEvent.Retry -> load()

            // Подписка — отдельная ручка: её повтор не должен дёргать список
            // тарифов, который уже на экране.
            SubscriptionEvent.CurrentRetry -> {
                updateState { copy(current = ScreenState.Loading) }
                viewModelScope.launch { applyCurrent(repository.current()) }
            }

            // История — третья ручка, и её повтор тоже сам по себе.
            SubscriptionEvent.ChargesRetry -> loadCharges(showLoading = true)

            SubscriptionEvent.ChargesLoadMore -> loadMoreCharges()

            is SubscriptionEvent.PeriodSelected -> updateState { copy(period = event.period) }

            is SubscriptionEvent.SubscribeClicked -> subscribe(event.planCode)
            is SubscriptionEvent.TrialClicked -> startTrial(event.planCode)
            SubscriptionEvent.RenewClicked -> renew()

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
            updateState {
                copy(
                    plans = ScreenState.Loading,
                    current = ScreenState.Loading,
                    charges = ScreenState.Loading,
                )
            }
        }
        resetCharges()
        updateState {
            copy(
                isRefreshing = refreshing,
                actionFailure = null,
                isLoadingMoreCharges = false,
                chargesLoadMoreFailure = null,
                // Прогноз продления считается от момента загрузки: часы
                // берутся из графа, а не в экране, иначе ни экран, ни его
                // превью не были бы детерминированными.
                now = clock.instant(),
            )
        }
        loadJob = viewModelScope.launch {
            // Аудитория тарифов зависит от роли: продавцу бэкенд показывает
            // свой набор (`plans?audience=BUSINESS`), и оформляются такие
            // тарифы отдельной ручкой.
            val audience = audience()
            // Три независимые ручки: последовательный запрос утроил бы время
            // до первого экрана без всякой причины.
            val plans = async { repository.plans(audience) }
            val current = async { repository.current() }
            val charges = async { repository.charges(fromPage = 0) }
            // Ответ дожидается снаружи `updateState`: тот принимает обычную
            // лямбду, и `await()` внутри неё не компилируется.
            val loadedPlans = plans.await().toListScreenState()
            updateState { copy(plans = loadedPlans) }
            applyCurrent(current.await())
            applyCharges(charges.await())
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

    /**
     * @param keepShownOnFailure оставить уже показанную историю, если запрос
     * не удался. Так перечитывается история **после действия**: человек
     * только что оформил или продлил подписку и ищет своё списание — заменить
     * список сообщением об ошибке в этот момент значит спрятать и то, что
     * было.
     */
    private fun loadCharges(showLoading: Boolean, keepShownOnFailure: Boolean = false) {
        resetCharges()
        updateState {
            copy(
                charges = if (showLoading) ScreenState.Loading else charges,
                isLoadingMoreCharges = false,
                chargesLoadMoreFailure = null,
            )
        }
        chargesJob = viewModelScope.launch {
            applyCharges(repository.charges(fromPage = 0), keepShownOnFailure)
        }
    }

    /**
     * Курсор истории и её догрузка сбрасываются вместе: страница, с которой
     * продолжать, приезжает в ответе, и после сброса до него «показать ещё»
     * ушло бы за ту же нулевую страницу — то есть не добавило бы ни строки.
     */
    private fun resetCharges() {
        chargesLoadMoreJob?.cancel()
        chargesJob?.cancel()
        nextChargesPage = 0
    }

    private fun applyCharges(
        result: ApiResult<SubscriptionChargePage>,
        keepShownOnFailure: Boolean = false,
    ) {
        when (result) {
            is ApiResult.Failure -> updateState {
                if (keepShownOnFailure && charges is ScreenState.Content) {
                    this
                } else {
                    copy(charges = ScreenState.Error(result.failure), chargesHasMore = false)
                }
            }

            is ApiResult.Success -> {
                nextChargesPage = result.data.nextPage
                updateState {
                    copy(
                        charges = if (result.data.items.isEmpty()) {
                            ScreenState.Empty
                        } else {
                            ScreenState.Content(result.data.items)
                        },
                        chargesHasMore = result.data.hasMore,
                    )
                }
            }
        }
    }

    /**
     * Догрузка истории списаний. Кнопкой, а не по достижению конца списка (как
     * в кошельке, issue #62): история здесь не последняя на экране — под ней
     * тарифы, и автотриггер срабатывал бы у всех, кто просто доскроллил до
     * них.
     *
     * Пустая страница уже показанного списка не стирает: платежи за подписку
     * могли кончиться, а платежи вообще — нет, и хвост тогда просто пропадает
     * вместе с кнопкой.
     *
     * Пока история грузится с нуля, догрузки нет: курсор уже сброшен на
     * нулевую страницу, и «показать ещё» ушло бы за неё же — то есть потратило
     * бы запрос и не добавило ни строки.
     */
    private fun loadMoreCharges() {
        val state = currentState
        if (!state.chargesHasMore || state.isLoadingMoreCharges) return
        if (chargesLoadMoreJob?.isActive == true) return
        if (chargesJob?.isActive == true || loadJob?.isActive == true) return

        val shown = (state.charges as? ScreenState.Content)?.data ?: emptyList()
        val fromPage = nextChargesPage
        updateState { copy(isLoadingMoreCharges = true, chargesLoadMoreFailure = null) }
        chargesLoadMoreJob = viewModelScope.launch {
            when (val result = repository.charges(fromPage = fromPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMoreCharges = false, chargesLoadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    nextChargesPage = result.data.nextPage
                    val merged = appended(shown, result.data.items)
                    updateState {
                        copy(
                            charges = if (merged.isEmpty()) {
                                ScreenState.Empty
                            } else {
                                ScreenState.Content(merged)
                            },
                            chargesHasMore = result.data.hasMore,
                            isLoadingMoreCharges = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Списание может приехать на двух соседних страницах, если история
     * пополнилась между запросами. В `LazyColumn` это дубликат ключа и
     * падение, поэтому дедупликация по id обязательна (issue #62).
     */
    private fun appended(
        shown: List<SubscriptionCharge>,
        next: List<SubscriptionCharge>,
    ): List<SubscriptionCharge> {
        val known = shown.mapTo(mutableSetOf(), SubscriptionCharge::id)
        return shown + next.filter { known.add(it.id) }
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
     * Продление (задача 9.2). Ручка та же, что у оформления, но тариф и период
     * берутся у самой подписки: человек, нажавший «продлить», просит ровно то,
     * что у него было, — переспрашивать это нечем и незачем.
     *
     * Кнопки у действующей подписки нет ([canRenew]): контракт не обещает, что
     * `subscribe` **прибавит** срок к оплаченному.
     */
    private fun renew() {
        val plan = currentState.renewablePlan ?: return
        if (currentState.isBusy) return

        val period = currentState.renewalPeriod
        updateState {
            copy(pending = SubscriptionAction.Renew, actionFailure = null, notice = null)
        }
        viewModelScope.launch {
            finish(repository.subscribe(plan, period), SubscriptionNotice.Renewed)
        }
    }

    /**
     * Общий хвост оформления, продления и пробного периода: подписка берётся
     * из ответа сервера, а если он её не назвал — перечитывается. Досчитывать
     * её на клиенте нельзя: срок, статус и грейс-период знает только бэкенд.
     *
     * История списаний после успеха перечитывается: за оформление и продление
     * списывают деньги, и списание, которого нет в истории, — самый быстрый
     * способ заставить человека сомневаться в платеже.
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
                            now = clock.instant(),
                        )
                    }
                } else {
                    updateState { copy(pending = null, notice = notice, now = clock.instant()) }
                    applyCurrent(repository.current())
                }
                loadCharges(showLoading = false, keepShownOnFailure = true)
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
