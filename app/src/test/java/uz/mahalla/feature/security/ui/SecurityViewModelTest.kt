package uz.mahalla.feature.security.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.security.domain.AppLockManager
import uz.mahalla.feature.security.domain.ServerPinStatus
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeSecurityRepository
import uz.mahalla.testutil.FakeSessionStore
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Настройки безопасности (issue #102): статус PIN и переключатель биометрии.
 *
 * Главное здесь — порядок при включении: датчик, потом код, потом сервер. Ни
 * один из трёх шагов не должен писать флаг раньше остальных.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeSecurityRepository()
    private var onboarding = FakeOnboardingRepository()

    @Test
    fun `status arrives from the backend`() = runTest {
        repository.status = ApiResult.Success(
            ServerPinStatus(pinSet = true, biometricEnabled = true, lockedSecondsRemaining = 15),
        )

        val viewModel = viewModel()

        val status = (viewModel.state.value.status as ScreenState.Content).data
        assertTrue(status.pinSet)
        assertTrue(status.locked)
    }

    @Test
    fun `enabling asks the sensor before anything is written`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(SecurityEvent.BiometricToggled(true))

        assertEquals(SecurityEffect.ShowBiometricPrompt, viewModel.effects.first())
        // Ни кода, ни запроса ещё не было: обещать вход по отпечатку до того,
        // как он сработал хоть раз, нельзя.
        assertNull(viewModel.state.value.pinPrompt)
        assertTrue(repository.biometricCalls.isEmpty())
    }

    @Test
    fun `sensor success opens the pin sheet, and the code goes to the backend`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))

        viewModel.onEvent(SecurityEvent.BiometricPromptSucceeded)
        assertEquals(true, viewModel.state.value.pinPrompt)

        viewModel.onEvent(SecurityEvent.PinChanged("123456"))

        assertEquals(listOf(true to "123456"), repository.biometricCalls)
        assertTrue(viewModel.state.value.biometricEnabled)
        assertNull(viewModel.state.value.pinPrompt)
    }

    @Test
    fun `disabling does not ask the sensor`() = runTest {
        onboarding = FakeOnboardingRepository(AppSettings(biometricEnabled = true))
        val viewModel = viewModel()

        viewModel.onEvent(SecurityEvent.BiometricToggled(false))

        // Человек как раз говорит, что пользоваться датчиком не будет.
        assertEquals(false, viewModel.state.value.pinPrompt)
        viewModel.onEvent(SecurityEvent.PinChanged("123456"))
        assertEquals(listOf(false to "123456"), repository.biometricCalls)
    }

    @Test
    fun `failed sensor stops the toggle`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))

        viewModel.onEvent(SecurityEvent.BiometricPromptFailed)

        assertTrue(viewModel.state.value.biometricPromptFailed)
        assertNull(viewModel.state.value.pinPrompt)
        assertFalse(viewModel.state.value.busy)
        assertTrue(repository.biometricCalls.isEmpty())
    }

    @Test
    fun `cancelled sensor is silent`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))

        viewModel.onEvent(SecurityEvent.BiometricPromptCancelled)

        assertFalse(viewModel.state.value.biometricPromptFailed)
        assertNull(viewModel.state.value.pinPrompt)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `refusal stays in the sheet with the server text`() = runTest {
        repository.biometricResult = ApiResult.Failure(ApiError.Business("PIN_INVALID"))
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))
        viewModel.onEvent(SecurityEvent.BiometricPromptSucceeded)

        viewModel.onEvent(SecurityEvent.PinChanged("000000"))

        // Закрыть шторку значило бы потерять объяснение (issue #34).
        assertEquals(true, viewModel.state.value.pinPrompt)
        assertEquals(ApiError.Business("PIN_INVALID"), viewModel.state.value.failure?.error)
        assertEquals("", viewModel.state.value.pin.code)
        assertFalse(viewModel.state.value.biometricEnabled)
    }

    @Test
    fun `dismissing the sheet cancels the toggle`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))
        viewModel.onEvent(SecurityEvent.BiometricPromptSucceeded)

        viewModel.onEvent(SecurityEvent.PinPromptDismissed)

        assertNull(viewModel.state.value.pinPrompt)
        assertFalse(viewModel.state.value.busy)
        assertTrue(repository.biometricCalls.isEmpty())
    }

    @Test
    fun `server answer wins over what was asked`() = runTest {
        repository.biometricResult = ApiResult.Success(false)
        val viewModel = viewModel()
        viewModel.onEvent(SecurityEvent.BiometricToggled(true))
        viewModel.onEvent(SecurityEvent.BiometricPromptSucceeded)

        viewModel.onEvent(SecurityEvent.PinChanged("123456"))

        assertFalse(viewModel.state.value.biometricEnabled)
    }

    @Test
    fun `missing sensor blocks enabling but not disabling`() = runTest {
        val off = viewModel(biometricStatus = BiometricStatus.NoHardware)
        assertFalse(off.state.value.canToggleBiometric)

        onboarding = FakeOnboardingRepository(AppSettings(biometricEnabled = true))
        val on = viewModel(biometricStatus = BiometricStatus.NoHardware)
        // Флаг мог остаться от устройства, где датчик работал: выключить его
        // человек должен иметь возможность всегда.
        assertTrue(on.state.value.canToggleBiometric)
    }

    @Test
    fun `screen resume rereads the status and the sensor`() = runTest {
        val viewModel = viewModel()
        repository.status = ApiResult.Success(
            ServerPinStatus(pinSet = false, biometricEnabled = false, lockedSecondsRemaining = 0),
        )

        viewModel.onEvent(SecurityEvent.ScreenResumed)

        val status = (viewModel.state.value.status as ScreenState.Content).data
        assertFalse(status.pinSet)
    }

    @Test
    fun `lock state is shown, not offered as a switch`() = runTest {
        val viewModel = viewModel()

        // Есть сессия и локальная копия PIN — замок вооружён.
        assertTrue(viewModel.state.value.appLockArmed)
    }

    @Test
    fun `lock is reported as off when there is no local copy`() = runTest {
        val viewModel = viewModel(pinStorage = FakePinStorage(initialPin = null))

        // Редкий отказ Keystore при смене PIN замок разоружает, и человек
        // должен узнать об этом не от вора.
        assertFalse(viewModel.state.value.appLockArmed)
    }

    @Test
    fun `failed status does not hide the screen`() = runTest {
        repository.status = FakeSecurityRepository.NETWORK_FAILURE

        val viewModel = viewModel()

        assertTrue(viewModel.state.value.status is ScreenState.Error)
        // Переключатель всё равно доступен: он ходит в сеть сам и объяснит
        // себя собственным отказом.
        assertTrue(viewModel.state.value.canToggleBiometric)
    }

    @Test
    fun `retry asks the backend again`() = runTest {
        repository.status = FakeSecurityRepository.NETWORK_FAILURE
        val viewModel = viewModel()
        repository.status = ApiResult.Success(
            ServerPinStatus(pinSet = true, biometricEnabled = false, lockedSecondsRemaining = 0),
        )

        viewModel.onEvent(SecurityEvent.RetryRequested)

        assertTrue((viewModel.state.value.status as ScreenState.Content).data.pinSet)
    }

    private fun viewModel(
        pinStorage: FakePinStorage = FakePinStorage(initialPin = "123456"),
        biometricStatus: BiometricStatus = BiometricStatus.Available,
    ) = SecurityViewModel(
        securityRepository = repository,
        onboardingRepository = onboarding,
        biometricAvailability = object : BiometricAvailability {
            override fun status(): BiometricStatus = biometricStatus
        },
        appLockManager = AppLockManager(
            sessionStore = FakeSessionStore(Session("a-1", "r-1")),
            pinStorage = pinStorage,
            clock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC),
        ),
    )
}
