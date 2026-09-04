package uz.mahalla.feature.security.ui.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.security.domain.AppLockManager
import uz.mahalla.feature.security.domain.SessionCheck
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeSecurityRepository
import uz.mahalla.testutil.FakeSessionStore
import uz.mahalla.testutil.MainDispatcherRule
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Экран блокировки (issue #102).
 *
 * Проверяется главное правило задачи: разблокировка держится на локальной
 * копии PIN, а сеть только продлевает серверную сессию. Обратный порядок
 * сделал бы приложение неоткрываемым без интернета — ровно тот риск, о
 * котором предупреждает issue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val pinStorage = FakePinStorage(initialPin = "123456")
    private val securityRepository = FakeSecurityRepository()
    private val authRepository = FakeAuthRepository(initialAuthorized = true)
    private val sessionStore = FakeSessionStore(Session("a-1", "r-1"))
    private val appLockManager = AppLockManager(
        sessionStore = sessionStore,
        pinStorage = pinStorage,
        clock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC),
    )
    private var onboarding = FakeOnboardingRepository(AppSettings(onboardingCompleted = true))

    @Test
    fun `field length follows the saved pin`() = runTest {
        // Четырёхзначный код прежней версии (issue #51): шесть ячеек означали
        // бы «ввести нечем».
        val viewModel = viewModel(pinStorage = FakePinStorage(initialPin = "1234"))

        assertEquals(4, viewModel.state.value.pin.length)
    }

    @Test
    fun `correct pin unlocks even when the network is gone`() = runTest {
        securityRepository.resumeResult = FakeSecurityRepository.NETWORK_FAILURE
        appLockManager.lockNow()
        val viewModel = viewModel()

        viewModel.onEvent(AppLockEvent.PinChanged("123456"))

        // Сеть отказала, а замок всё равно снят: локальная копия — тот же код,
        // который принял сервер.
        assertFalse(appLockManager.locked.value)
        assertEquals(listOf("123456"), securityRepository.resumeCalls)
        assertNotNull(viewModel.state.value.apiFailure)
    }

    @Test
    fun `successful unlock continues the server session`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()

        viewModel.onEvent(AppLockEvent.PinChanged("123456"))

        assertEquals(listOf("123456"), securityRepository.resumeCalls)
        assertNull(viewModel.state.value.apiFailure)
        assertFalse(appLockManager.locked.value)
    }

    @Test
    fun `wrong pin spends an attempt and keeps the lock`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()

        viewModel.onEvent(AppLockEvent.PinChanged("000000"))

        assertTrue(appLockManager.locked.value)
        assertEquals(AppLockError.WRONG_PIN, viewModel.state.value.error)
        assertEquals(4, viewModel.state.value.attemptsLeft)
        // Серверу неверный код не отправляем: ответ был бы тот же, а трафик и
        // ожидание — лишние.
        assertTrue(securityRepository.resumeCalls.isEmpty())
    }

    @Test
    fun `storage failure does not spend an attempt`() = runTest {
        appLockManager.lockNow()
        val failing = FakePinStorage(initialPin = "123456")
            .apply { failure = IOException("keystore") }
        val viewModel = viewModel(pinStorage = failing)

        viewModel.onEvent(AppLockEvent.PinChanged("123456"))

        assertEquals(AppLockError.STORAGE, viewModel.state.value.error)
        // Человек не виноват, а лимит стоил бы ему входа на ровном месте.
        assertEquals(AppLockState.MAX_ATTEMPTS, viewModel.state.value.attemptsLeft)
        assertTrue(appLockManager.locked.value)
    }

    @Test
    fun `exhausted attempts log out instead of locking forever`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()

        repeat(AppLockState.MAX_ATTEMPTS) {
            viewModel.onEvent(AppLockEvent.PinChanged("000000"))
        }

        assertEquals(AppLockEffect.AuthRestartRequired, viewModel.effects.first())
        assertEquals(1, authRepository.logoutCount)
        // Следующий запуск обязан привести на вход, а не в main, где каждый
        // запрос ответит 401.
        assertFalse(onboarding.current.onboardingCompleted)
        assertFalse(appLockManager.locked.value)
    }

    @Test
    fun `biometric success unlocks and asks nothing of the server`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel(biometricEnabled = true)

        viewModel.onEvent(AppLockEvent.BiometricSucceeded)

        assertFalse(appLockManager.locked.value)
        // Ручки «продолжить сессию по биометрии» у бэкенда нет — отправлять
        // нечего.
        assertTrue(securityRepository.resumeCalls.isEmpty())
    }

    @Test
    fun `prompt is offered when biometric is on`() = runTest {
        val viewModel = viewModel(biometricEnabled = true)

        assertTrue(viewModel.state.value.canUseBiometric)
        assertEquals(AppLockEffect.ShowBiometricPrompt, viewModel.effects.first())
    }

    @Test
    fun `prompt is not offered when the sensor is gone`() = runTest {
        val viewModel = viewModel(
            biometricEnabled = true,
            biometricStatus = BiometricStatus.NotEnrolled,
        )

        // Флаг остался с тех пор, когда отпечаток был добавлен: кнопка,
        // которая ничего не откроет, читается как сломанная.
        assertFalse(viewModel.state.value.canUseBiometric)
    }

    @Test
    fun `dead session sends the user back to sign in`() = runTest {
        securityRepository.sessionCheck = ApiResult.Success(
            SessionCheck(valid = false, pinRequired = false, reason = "SESSION_REVOKED"),
        )
        appLockManager.lockNow()

        val viewModel = viewModel()

        // Ждать PIN от того, у кого сессии нет, значит запереть его насовсем.
        assertEquals(AppLockEffect.AuthRestartRequired, viewModel.effects.first())
        assertEquals(1, authRepository.logoutCount)
        assertFalse(appLockManager.locked.value)
    }

    @Test
    fun `unreachable server does not log anyone out`() = runTest {
        securityRepository.sessionCheck = FakeSecurityRepository.NETWORK_FAILURE
        appLockManager.lockNow()

        viewModel()

        // «Спросить не удалось» — не «сессии нет».
        assertEquals(0, authRepository.logoutCount)
        assertTrue(appLockManager.locked.value)
    }

    @Test
    fun `forgot pin logs out and clears the onboarding flag`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()

        viewModel.onEvent(AppLockEvent.ForgotPin)

        assertEquals(AppLockEffect.AuthRestartRequired, viewModel.effects.first())
        assertEquals(1, authRepository.logoutCount)
        assertFalse(onboarding.current.onboardingCompleted)
        assertFalse(appLockManager.locked.value)
    }

    @Test
    fun `failed sensor is explained without spending an attempt`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel(biometricEnabled = true)

        viewModel.onEvent(AppLockEvent.BiometricFailed)

        assertEquals(AppLockError.BIOMETRIC_FAILED, viewModel.state.value.error)
        assertEquals(AppLockState.MAX_ATTEMPTS, viewModel.state.value.attemptsLeft)
        assertTrue(appLockManager.locked.value)
    }

    @Test
    fun `cancelled prompt is not an error`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel(biometricEnabled = true)

        viewModel.onEvent(AppLockEvent.BiometricCancelled)

        assertNull(viewModel.state.value.error)
        assertTrue(appLockManager.locked.value)
    }

    @Test
    fun `server refusal of resume does not re-lock the app`() = runTest {
        securityRepository.resumeResult = ApiResult.Failure(ApiError.Business("SESSION_EXPIRED"))
        appLockManager.lockNow()
        val viewModel = viewModel()

        viewModel.onEvent(AppLockEvent.PinChanged("123456"))

        // Отказ объясняет, почему следующий запрос может ответить 401, но
        // решать, пускать ли человека в его же приложение, он не должен.
        assertFalse(appLockManager.locked.value)
        assertEquals(
            ApiError.Business("SESSION_EXPIRED"),
            viewModel.state.value.apiFailure?.error,
        )
    }

    private fun viewModel(
        pinStorage: FakePinStorage = this.pinStorage,
        biometricEnabled: Boolean = false,
        biometricStatus: BiometricStatus = BiometricStatus.Available,
    ): AppLockViewModel {
        onboarding = FakeOnboardingRepository(
            AppSettings(onboardingCompleted = true, biometricEnabled = biometricEnabled),
        )
        return AppLockViewModel(
            pinStorage = pinStorage,
            onboardingRepository = onboarding,
            biometricAvailability = object : BiometricAvailability {
                override fun status(): BiometricStatus = biometricStatus
            },
            securityRepository = securityRepository,
            appLockManager = appLockManager,
            authRepository = authRepository,
            // Экран шлёт это событие на каждом появлении оверлея — тест
            // обязан вести себя так же, иначе он проверял бы `init`, которого
            // у этой ViewModel нет.
        ).also { it.onEvent(AppLockEvent.Shown) }
    }

    @Test
    fun `a second lock prepares itself again`() = runTest {
        // ViewModel привязана к Activity и переживает снятие оверлея: если бы
        // подготовка стояла в `init`, второе запирание осталось бы без
        // проверки сессии и без промпта.
        val viewModel = viewModel(biometricEnabled = true)
        viewModel.onEvent(AppLockEvent.PinChanged("123456"))
        val checksAfterFirst = securityRepository.sessionCheckCount

        appLockManager.lockNow()
        viewModel.onEvent(AppLockEvent.Shown)

        assertEquals(checksAfterFirst + 1, securityRepository.sessionCheckCount)
        assertEquals(AppLockEffect.ShowBiometricPrompt, viewModel.effects.first())
    }

    @Test
    fun `a new lock does not show the previous error`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()
        viewModel.onEvent(AppLockEvent.PinChanged("000000"))
        assertEquals(AppLockError.WRONG_PIN, viewModel.state.value.error)

        viewModel.onEvent(AppLockEvent.Shown)

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a successful unlock restores the attempt budget`() = runTest {
        appLockManager.lockNow()
        val viewModel = viewModel()
        viewModel.onEvent(AppLockEvent.PinChanged("000000"))
        assertEquals(4, viewModel.state.value.attemptsLeft)

        viewModel.onEvent(AppLockEvent.PinChanged("123456"))

        assertEquals(AppLockState.MAX_ATTEMPTS, viewModel.state.value.attemptsLeft)
    }
}
