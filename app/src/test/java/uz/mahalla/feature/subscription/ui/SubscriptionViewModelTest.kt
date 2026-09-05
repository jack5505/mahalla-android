package uz.mahalla.feature.subscription.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.FakeSubscriptionRepository
import uz.mahalla.testutil.MainDispatcherRule

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

    private fun viewModel(
        repository: FakeSubscriptionRepository,
        role: UserRole = UserRole.Customer,
    ) = SubscriptionViewModel(
        repository = repository,
        roleRepository = FakeRoleRepository(RoleProfile(role = role)),
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
    ) = Subscription(
        planCode = "PRO",
        status = status,
        billingPeriod = period,
        autoRenew = autoRenew,
        isTrial = isTrial,
        isActive = status == SubscriptionStatus.Active,
    )
}
