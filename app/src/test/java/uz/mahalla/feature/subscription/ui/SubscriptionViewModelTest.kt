package uz.mahalla.feature.subscription.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionCharge
import uz.mahalla.feature.subscription.domain.SubscriptionChargePage
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionStage
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import uz.mahalla.feature.subscription.domain.stage
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.FakeSubscriptionRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant

/**
 * Экран подписки (issue #103): тарифы и текущая подписка — две независимые
 * ручки, а всё, что касается денег, приходит с сервера.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `plans and the current subscription are loaded on open`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
        }

        val state = viewModel(repository).state.value

        assertEquals(listOf("PRO"), (state.plans as ScreenState.Content).data.map { it.code })
        assertEquals("PRO", (state.current as ScreenState.Content).data.planCode)
        assertEquals(BillingPeriod.Monthly, state.period)
    }

    @Test
    fun `no subscription is an empty state, not an error`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
        }

        val state = viewModel(repository).state.value

        assertTrue(state.current is ScreenState.Empty)
        // Ровно из этого состояния и предлагается пробный период.
        assertTrue(state.trialAvailable)
    }

    @Test
    fun `a refusal of the plans does not hide the subscription that is already paid for`() =
        runTest {
            val repository = FakeSubscriptionRepository().apply {
                plans = ApiResult.Failure(ApiError.NoConnection)
                currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            }

            val state = viewModel(repository).state.value

            assertTrue(state.plans is ScreenState.Error)
            assertTrue(state.current is ScreenState.Content)
        }

    @Test
    fun `a provider is shown the business plans`() = runTest {
        val repository = FakeSubscriptionRepository()

        viewModel(repository, role = UserRole.Provider)

        assertEquals(listOf(PlanAudience.Business), repository.requestedAudiences)
    }

    @Test
    fun `a customer is shown the user plans`() = runTest {
        val repository = FakeSubscriptionRepository()

        viewModel(repository, role = UserRole.Customer)

        assertEquals(listOf(PlanAudience.User), repository.requestedAudiences)
    }

    @Test
    fun `subscribing sends the selected period and shows the answer of the server`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            subscribeResult = ApiResult.Success(subscription(period = BillingPeriod.Yearly))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.PeriodSelected(BillingPeriod.Yearly))
        viewModel.onEvent(SubscriptionEvent.SubscribeClicked("PRO"))

        assertEquals(listOf("PRO" to BillingPeriod.Yearly), repository.subscribeRequests)
        val state = viewModel.state.value
        assertEquals(
            BillingPeriod.Yearly,
            (state.current as ScreenState.Content).data.billingPeriod,
        )
        assertEquals(SubscriptionNotice.Subscribed, state.notice)
        assertNull(state.pending)
    }

    @Test
    fun `a confirmed subscription without a body is re-read from the server`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(null),
                ApiResult.Success(subscription()),
            )
            subscribeResult = ApiResult.Success(null)
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.SubscribeClicked("PRO"))

        assertEquals(2, repository.currentCount)
        assertEquals("PRO", (viewModel.state.value.current as ScreenState.Content).data.planCode)
    }

    @Test
    fun `a refusal of subscribing is shown and does not touch the subscription`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            subscribeResult = ApiResult.Failure(ApiError.Business("INSUFFICIENT_FUNDS"))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.SubscribeClicked("PRO"))

        val state = viewModel.state.value
        assertEquals(ApiError.Business("INSUFFICIENT_FUNDS"), state.actionFailure?.error)
        assertTrue(state.current is ScreenState.Empty)
        assertNull(state.notice)
        assertNull(state.pending)
    }

    @Test
    fun `an unknown plan is not subscribed`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.SubscribeClicked("GONE"))

        assertTrue(repository.subscribeRequests.isEmpty())
    }

    @Test
    fun `the trial is not offered to someone who already has a subscription`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan(trialDays = 7)))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.TrialClicked("PRO"))

        assertFalse(viewModel.state.value.trialAvailable)
        assertTrue(repository.trialRequests.isEmpty())
    }

    @Test
    fun `the trial starts and says so`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan(trialDays = 7)))
            trialResult = ApiResult.Success(subscription(isTrial = true))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.TrialClicked("PRO"))

        assertEquals(listOf("PRO"), repository.trialRequests)
        val state = viewModel.state.value
        assertTrue((state.current as ScreenState.Content).data.isTrial)
        assertEquals(SubscriptionNotice.TrialStarted, state.notice)
    }

    @Test
    fun `cancelling asks for confirmation first`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.CancelRequested)

        assertTrue(viewModel.state.value.confirmCancel)
        assertEquals(0, repository.cancelCount)
    }

    @Test
    fun `a confirmed cancellation re-reads the subscription`() = runTest {
        // Бэкенд может оставить доступ до конца оплаченного срока — досчитывать
        // это на клиенте нельзя.
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription()),
                ApiResult.Success(subscription(status = SubscriptionStatus.Cancelled)),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.CancelRequested)
        viewModel.onEvent(SubscriptionEvent.CancelConfirmed)

        assertEquals(1, repository.cancelCount)
        val state = viewModel.state.value
        assertEquals(
            SubscriptionStatus.Cancelled,
            (state.current as ScreenState.Content).data.status,
        )
        assertEquals(SubscriptionNotice.Cancelled, state.notice)
        assertFalse(state.confirmCancel)
    }

    @Test
    fun `a refusal of cancelling keeps the subscription as it was`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            cancelResult = ApiResult.Failure(ApiError.Business("ALREADY_CANCELLED"))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.CancelRequested)
        viewModel.onEvent(SubscriptionEvent.CancelConfirmed)

        val state = viewModel.state.value
        assertEquals(ApiError.Business("ALREADY_CANCELLED"), state.actionFailure?.error)
        assertEquals(
            SubscriptionStatus.Active,
            (state.current as ScreenState.Content).data.status,
        )
    }

    @Test
    fun `an already cancelled subscription is not cancelled again`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription(status = SubscriptionStatus.Cancelled)),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.CancelRequested)

        assertFalse(viewModel.state.value.confirmCancel)
    }

    @Test
    fun `auto-renew is applied in place after the server confirms it`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(ApiResult.Success(subscription(autoRenew = true)))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.AutoRenewToggled(enabled = false))

        assertEquals(listOf(false), repository.autoRenewRequests)
        assertFalse((viewModel.state.value.current as ScreenState.Content).data.autoRenew)
        // Ради одного флага перечитывать всю подписку незачем: исход запроса —
        // ровно то, что ушло на сервер.
        assertEquals(1, repository.currentCount)
    }

    @Test
    fun `a refusal returns the switch to where it was and explains why`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(ApiResult.Success(subscription(autoRenew = true)))
            autoRenewResult = ApiResult.Failure(ApiError.NoConnection)
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.AutoRenewToggled(enabled = false))

        val state = viewModel.state.value
        assertTrue((state.current as ScreenState.Content).data.autoRenew)
        assertEquals(ApiError.NoConnection, state.actionFailure?.error)
    }

    @Test
    fun `the same value of auto-renew does not reach the network`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            currentAnswers = mutableListOf(ApiResult.Success(subscription(autoRenew = true)))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.AutoRenewToggled(enabled = true))

        assertTrue(repository.autoRenewRequests.isEmpty())
    }

    @Test
    fun `returning to the screen re-reads everything`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
        }
        val viewModel = viewModel(repository)

        // Первый resume — это открытие экрана, тарифы уже запросил `init`.
        viewModel.onEvent(SubscriptionEvent.ScreenResumed)
        assertEquals(1, repository.currentCount)

        viewModel.onEvent(SubscriptionEvent.ScreenResumed)

        assertEquals(2, repository.currentCount)
        assertEquals(2, repository.requestedAudiences.size)
    }

    @Test
    fun `the retry of the subscription does not touch the plans`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Failure(ApiError.Timeout))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.CurrentRetry)

        assertEquals(2, repository.currentCount)
        assertEquals(1, repository.requestedAudiences.size)
    }

    @Test
    fun `the state of the subscription reaches the screen with its consequences`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
        }

        // Активна: срок далеко, продлевать нечего — она продлится сама.
        repository.currentAnswers = mutableListOf(
            ApiResult.Success(subscription(daysRemaining = 21)),
        )
        viewModel(repository).state.value.let { state ->
            assertEquals(SubscriptionStage.Active, state.stage())
            assertNull(state.renewablePlan)
        }

        // Истекает: последние дни срока. Статуса под это у бэкенда нет, а
        // кнопки продления по-прежнему нет — подписка ещё действует.
        repository.currentAnswers = mutableListOf(
            ApiResult.Success(subscription(daysRemaining = 2)),
        )
        viewModel(repository).state.value.let { state ->
            assertEquals(SubscriptionStage.ExpiringSoon, state.stage())
            assertNull(state.renewablePlan)
        }

        // Истекла: продлевать есть что, и тариф для этого нашёлся в списке.
        repository.currentAnswers = mutableListOf(
            ApiResult.Success(subscription(status = SubscriptionStatus.Expired)),
        )
        viewModel(repository).state.value.let { state ->
            assertEquals(SubscriptionStage.Expired, state.stage())
            assertEquals("PRO", state.renewablePlan?.code)
        }

        // Отменена — даже если доступ ещё остался до конца оплаченного срока;
        // вернуть её можно только оформлением заново.
        repository.currentAnswers = mutableListOf(
            ApiResult.Success(
                subscription(status = SubscriptionStatus.Cancelled, daysRemaining = 20),
            ),
        )
        viewModel(repository).state.value.let { state ->
            assertEquals(SubscriptionStage.Cancelled, state.stage())
            assertEquals("PRO", state.renewablePlan?.code)
        }
    }

    @Test
    fun `renewal is not offered when the plan of the subscription is not in the list`() = runTest {
        // Без тарифа неизвестно ни на что подписывать, ни какая это ручка: у
        // бизнес-тарифов своя.
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan().copy(code = "BASIC")))
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription(status = SubscriptionStatus.Expired)),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.RenewClicked)

        assertNull(viewModel.state.value.renewablePlan)
        assertNull(viewModel.state.value.renewsUntil)
        assertTrue(repository.subscribeRequests.isEmpty())
    }

    @Test
    fun `a free plan is not offered for renewal`() = runTest {
        // Оформлять бесплатный тариф нечего — это то, что человек и так
        // получает без подписки.
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(
                listOf(SubscriptionPlan(code = "PRO", isFree = true)),
            )
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription(status = SubscriptionStatus.Expired)),
            )
        }

        assertNull(viewModel(repository).state.value.renewablePlan)
    }

    @Test
    fun `the next charge is shown only where it will happen`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(
                    subscription(autoRenew = true, daysRemaining = 21, expiresAt = EXPIRES),
                ),
            )
        }

        val state = viewModel(repository).state.value

        // Это не расчёт, а `expiresAt` сервера: спишут в конце оплаченного срока.
        assertEquals(EXPIRES, state.nextChargeAt)
        // Продлевать действующую подписку руками нечем — и незачем.
        assertNull(state.renewablePlan)
        assertNull(state.renewsUntil)
    }

    @Test
    fun `an expired subscription offers renewal and a forecast of its term`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(
                    subscription(
                        status = SubscriptionStatus.Expired,
                        autoRenew = true,
                        expiresAt = Instant.parse("2026-01-04T09:00:00Z"),
                    ),
                ),
            )
        }

        val state = viewModel(repository).state.value

        assertEquals("PRO", state.renewablePlan?.code)
        // Срок пойдёт с момента оплаты: прибавлять месяц к январской дате нечего.
        assertEquals(Instant.parse("2026-10-08T07:00:00Z"), state.renewsUntil)
        // Списания по автопродлению у истёкшей подписки не будет, сколько бы
        // ни стоял флаг.
        assertNull(state.nextChargeAt)
    }

    @Test
    fun `renewal takes the plan and the period of the subscription itself`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(
                    subscription(
                        status = SubscriptionStatus.Expired,
                        period = BillingPeriod.Yearly,
                    ),
                ),
                ApiResult.Success(subscription(daysRemaining = 365)),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.RenewClicked)

        // Период — оплаченный, а не выбранный на экране: продлевают то, что
        // было.
        assertEquals(listOf("PRO" to BillingPeriod.Yearly), repository.subscribeRequests)
        assertEquals(BillingPeriod.Monthly, viewModel.state.value.period)
        assertEquals(SubscriptionNotice.Renewed, viewModel.state.value.notice)
        assertNull(viewModel.state.value.pending)
        // Продление — это списание: история перечитывается, иначе платёж
        // выглядел бы потерянным.
        assertEquals(listOf(0, 0), repository.chargeRequests)
    }

    @Test
    fun `there is nothing to renew in a subscription that continues by itself`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription(daysRemaining = 21)),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.RenewClicked)

        // `subscribe` из контракта не обещает прибавить срок к оплаченному.
        assertTrue(repository.subscribeRequests.isEmpty())
    }

    @Test
    fun `a refusal of renewal does not touch the subscription`() = runTest {
        val expired = subscription(status = SubscriptionStatus.Expired)
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(expired))
            subscribeResult = ApiResult.Failure(ApiError.Business("PAYMENT_REQUIRED"))
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.RenewClicked)

        val state = viewModel.state.value
        assertEquals(expired, (state.current as ScreenState.Content).data)
        assertEquals(ApiError.Business("PAYMENT_REQUIRED"), state.actionFailure?.error)
        assertNull(state.notice)
        assertNull(state.pending)
    }

    @Test
    fun `the history of charges is loaded together with the subscription`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(
                    SubscriptionChargePage(
                        items = listOf(charge("pay-1")),
                        hasMore = true,
                        nextPage = 1,
                    ),
                ),
            )
        }

        val state = viewModel(repository).state.value

        assertEquals(listOf("pay-1"), (state.charges as ScreenState.Content).data.map { it.id })
        assertTrue(state.chargesHasMore)
        assertEquals(listOf(0), repository.chargeRequests)
    }

    @Test
    fun `a refusal of the history hides neither the subscription nor the plans`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(ApiResult.Failure(ApiError.NoConnection))
        }

        val state = viewModel(repository).state.value

        assertEquals(ApiError.NoConnection, (state.charges as ScreenState.Error).error)
        assertTrue(state.current is ScreenState.Content)
        assertTrue(state.plans is ScreenState.Content)
        assertFalse(state.chargesHasMore)
    }

    @Test
    fun `an empty history is an empty state, not an error`() = runTest {
        // За подписку могли ещё не списывать: пробный период, первый день.
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
        }

        val state = viewModel(repository).state.value

        assertTrue(state.charges is ScreenState.Empty)
    }

    @Test
    fun `show more appends the next page of the history and continues from its page`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(
                    SubscriptionChargePage(
                        items = listOf(charge("pay-1")),
                        hasMore = true,
                        nextPage = 2,
                    ),
                ),
                ApiResult.Success(
                    SubscriptionChargePage(
                        // Первое списание приехало повторно: история могла
                        // пополниться между запросами, а дубликат ключа в
                        // `LazyColumn` — это падение.
                        items = listOf(charge("pay-1"), charge("pay-2")),
                        hasMore = false,
                        nextPage = 3,
                    ),
                ),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.ChargesLoadMore)

        val state = viewModel.state.value
        assertEquals(
            listOf("pay-1", "pay-2"),
            (state.charges as ScreenState.Content).data.map { it.id },
        )
        // Продолжение — со страницы сервера, а не со второй по счёту.
        assertEquals(listOf(0, 2), repository.chargeRequests)
        assertFalse(state.chargesHasMore)
        assertFalse(state.isLoadingMoreCharges)
    }

    @Test
    fun `a refusal of the load more does not erase the shown charges`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(
                    SubscriptionChargePage(
                        items = listOf(charge("pay-1")),
                        hasMore = true,
                        nextPage = 1,
                    ),
                ),
                ApiResult.Failure(ApiError.NoConnection),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.ChargesLoadMore)

        val state = viewModel.state.value
        assertEquals(listOf("pay-1"), (state.charges as ScreenState.Content).data.map { it.id })
        assertEquals(ApiError.NoConnection, state.chargesLoadMoreFailure?.error)
        assertFalse(state.isLoadingMoreCharges)
    }

    @Test
    fun `an empty history with a tail can still be continued`() = runTest {
        // Фильтр по назначению клиентский: «списаний нет» может значить всего
        // лишь «на просмотренных страницах платежей их не было». Хвост поэтому
        // остаётся, и догрузка находит списание на следующих страницах.
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(SubscriptionChargePage(hasMore = true, nextPage = 5)),
                ApiResult.Success(
                    SubscriptionChargePage(items = listOf(charge("pay-1")), nextPage = 6),
                ),
            )
        }
        val viewModel = viewModel(repository)

        assertTrue(viewModel.state.value.charges is ScreenState.Empty)
        assertTrue(viewModel.state.value.chargesHasMore)

        viewModel.onEvent(SubscriptionEvent.ChargesLoadMore)

        assertEquals(listOf(0, 5), repository.chargeRequests)
        assertEquals(
            listOf("pay-1"),
            (viewModel.state.value.charges as ScreenState.Content).data.map { it.id },
        )
    }

    @Test
    fun `load more does not go while the history is being reloaded`() = runTest {
        // Курсор к этому моменту уже сброшен на нулевую страницу: запрос ушёл
        // бы за неё же и не добавил ни строки.
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(
                    SubscriptionChargePage(
                        items = listOf(charge("pay-1")),
                        hasMore = true,
                        nextPage = 1,
                    ),
                ),
            )
        }
        val viewModel = viewModel(repository)
        // Перечит истории повис на ответе сервера.
        repository.chargeGate = CompletableDeferred()
        viewModel.onEvent(SubscriptionEvent.ChargesRetry)

        viewModel.onEvent(SubscriptionEvent.ChargesLoadMore)

        assertEquals(listOf(0, 0), repository.chargeRequests)
        assertFalse(viewModel.state.value.isLoadingMoreCharges)
        repository.chargeGate?.complete(Unit)
    }

    @Test
    fun `a second return to the screen does not start a second load`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
        }
        val viewModel = viewModel(repository)
        // Первое возвращение повисло на истории; в состоянии этого не видно —
        // ни скелетона, ни `isRefreshing` при обновлении поверх данных нет.
        repository.chargeGate = CompletableDeferred()
        viewModel.onEvent(SubscriptionEvent.ScreenResumed)

        viewModel.onEvent(SubscriptionEvent.ScreenResumed)

        // Иначе в каждую ручку ушло бы по два запроса, а состояние осталось бы
        // от того, кто ответил последним.
        assertEquals(2, repository.requestedAudiences.size)
        assertEquals(2, repository.currentCount)
        repository.chargeGate?.complete(Unit)
    }

    @Test
    fun `a failed reload of the history after renewal does not erase it`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(
                ApiResult.Success(subscription(status = SubscriptionStatus.Expired)),
            )
            chargeAnswers = mutableListOf(
                ApiResult.Success(SubscriptionChargePage(items = listOf(charge("pay-1")))),
                ApiResult.Failure(ApiError.NoConnection),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.RenewClicked)

        // Человек только что заплатил и ищет своё списание: заменить список
        // ошибкой значило бы спрятать и то, что было.
        val state = viewModel.state.value
        assertEquals(listOf("pay-1"), (state.charges as ScreenState.Content).data.map { it.id })
        assertEquals(SubscriptionNotice.Renewed, state.notice)
    }

    @Test
    fun `there is nothing to load more without a tail`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Success(SubscriptionChargePage(items = listOf(charge("pay-1")))),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.ChargesLoadMore)

        assertEquals(listOf(0), repository.chargeRequests)
    }

    @Test
    fun `a retry of the history does not touch the plans and the subscription`() = runTest {
        val repository = FakeSubscriptionRepository().apply {
            plans = ApiResult.Success(listOf(plan()))
            currentAnswers = mutableListOf(ApiResult.Success(subscription()))
            chargeAnswers = mutableListOf(
                ApiResult.Failure(ApiError.NoConnection),
                ApiResult.Success(SubscriptionChargePage(items = listOf(charge("pay-1")))),
            )
        }
        val viewModel = viewModel(repository)

        viewModel.onEvent(SubscriptionEvent.ChargesRetry)

        assertEquals(listOf(0, 0), repository.chargeRequests)
        assertEquals(1, repository.requestedAudiences.size)
        assertEquals(1, repository.currentCount)
        assertTrue(viewModel.state.value.charges is ScreenState.Content)
    }

    /** Состояние подписки, приехавшей на экран. */
    private fun SubscriptionState.stage(): SubscriptionStage =
        (current as ScreenState.Content).data.stage

    private fun charge(id: String) = SubscriptionCharge(
        id = id,
        amountSum = 49_000,
        status = ChargeStatus.Paid,
        provider = ChargeProvider.Payme,
        createdAt = EXPIRES,
    )

    private fun viewModel(
        repository: FakeSubscriptionRepository,
        role: UserRole = UserRole.Customer,
        now: Instant = NOW,
    ) = SubscriptionViewModel(
        repository = repository,
        roleRepository = FakeRoleRepository(RoleProfile(role = role)),
        // Часы фиксированы: от них зависит прогноз продления, а «сегодня» в
        // тесте не должно зависеть от дня прогона.
        clock = Clock.fixed(now, DateTimeFormatters.AppZone),
    )

    private fun plan(trialDays: Int = 0) = SubscriptionPlan(
        code = "PRO",
        name = "Pro",
        monthlySum = 49_000,
        yearlySum = 470_000,
        trialDays = trialDays,
    )

    private fun subscription(
        status: SubscriptionStatus = SubscriptionStatus.Active,
        period: BillingPeriod = BillingPeriod.Monthly,
        autoRenew: Boolean = false,
        isTrial: Boolean = false,
        expiresAt: Instant? = null,
        daysRemaining: Long? = null,
    ) = Subscription(
        planCode = "PRO",
        status = status,
        billingPeriod = period,
        autoRenew = autoRenew,
        isTrial = isTrial,
        expiresAt = expiresAt,
        daysRemaining = daysRemaining,
        isActive = status == SubscriptionStatus.Active,
    )

    private companion object {
        /** «Сегодня» тестов: 8 сентября 2026, полдень в Ташкенте. */
        val NOW: Instant = Instant.parse("2026-09-08T07:00:00Z")

        /** Конец оплаченного срока — месяц спустя. */
        val EXPIRES: Instant = Instant.parse("2026-10-08T07:00:00Z")
    }
}
