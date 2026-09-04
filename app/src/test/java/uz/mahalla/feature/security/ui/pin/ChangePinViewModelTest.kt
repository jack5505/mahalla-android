package uz.mahalla.feature.security.ui.pin

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
import uz.mahalla.testutil.FakeSecurityRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Смена PIN из настроек безопасности (issue #102). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangePinViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeSecurityRepository()

    @Test
    fun `three codes in a row reach the backend`() = runTest {
        val viewModel = ChangePinViewModel(repository)

        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        assertEquals(ChangePinStage.New, viewModel.state.value.stage)
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        assertEquals(ChangePinStage.Confirm, viewModel.state.value.stage)
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        assertEquals(listOf("111111" to "222222"), repository.changeCalls)
        assertTrue(viewModel.state.value.done)
        assertEquals("", viewModel.state.value.pin.code)
    }

    @Test
    fun `a new code equal to the current one is refused before the network`() = runTest {
        val viewModel = ChangePinViewModel(repository)

        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))

        // «Сменил PIN на тот же самый» — не смена: человек уверен, что
        // защитился, а не защитился ничем.
        assertEquals(ChangePinError.SAME_AS_CURRENT, viewModel.state.value.error)
        assertEquals(ChangePinStage.New, viewModel.state.value.stage)
        assertTrue(repository.changeCalls.isEmpty())
    }

    @Test
    fun `a mismatched repeat only asks for the new code again`() = runTest {
        val viewModel = ChangePinViewModel(repository)

        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("333333"))

        assertEquals(ChangePinError.MISMATCH, viewModel.state.value.error)
        // Текущий код переспрашивать незачем: его уже ввели верно.
        assertEquals(ChangePinStage.New, viewModel.state.value.stage)
        assertTrue(repository.changeCalls.isEmpty())
    }

    @Test
    fun `refusal shows the server text and restarts from the current code`() = runTest {
        repository.changeResult = ApiResult.Failure(ApiError.Business("PIN_INVALID"))
        val viewModel = ChangePinViewModel(repository)

        viewModel.onEvent(ChangePinEvent.PinChanged("999999"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        assertEquals(
            ApiError.Business("PIN_INVALID"),
            viewModel.state.value.apiFailure?.error,
        )
        assertEquals(ChangePinStage.Current, viewModel.state.value.stage)
        assertFalse(viewModel.state.value.done)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `a second attempt after a refusal sends the newly typed codes`() = runTest {
        repository.changeResult = ApiResult.Failure(ApiError.Business("PIN_INVALID"))
        val viewModel = ChangePinViewModel(repository)
        viewModel.onEvent(ChangePinEvent.PinChanged("999999"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        repository.changeResult = ApiResult.Success(Unit)
        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        // Прежние коды забыты: иначе второй запрос ушёл бы с кодом, который
        // сервер уже отверг.
        assertEquals(
            listOf("999999" to "222222", "111111" to "222222"),
            repository.changeCalls,
        )
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `typing clears the previous refusal`() = runTest {
        repository.changeResult = ApiResult.Failure(ApiError.Business("PIN_INVALID"))
        val viewModel = ChangePinViewModel(repository)
        viewModel.onEvent(ChangePinEvent.PinChanged("999999"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        viewModel.onEvent(ChangePinEvent.PinChanged("1"))

        assertNull(viewModel.state.value.apiFailure)
    }

    @Test
    fun `restart returns to the first step and forgets everything`() = runTest {
        val viewModel = ChangePinViewModel(repository)
        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        viewModel.onEvent(ChangePinEvent.Restart)

        assertEquals(ChangePinStage.Current, viewModel.state.value.stage)
        assertEquals("", viewModel.state.value.pin.code)
        viewModel.onEvent(ChangePinEvent.PinChanged("333333"))
        viewModel.onEvent(ChangePinEvent.PinChanged("444444"))
        viewModel.onEvent(ChangePinEvent.PinChanged("444444"))
        assertEquals(listOf("333333" to "444444"), repository.changeCalls)
    }

    @Test
    fun `input after success changes nothing`() = runTest {
        val viewModel = ChangePinViewModel(repository)
        viewModel.onEvent(ChangePinEvent.PinChanged("111111"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))
        viewModel.onEvent(ChangePinEvent.PinChanged("222222"))

        viewModel.onEvent(ChangePinEvent.PinChanged("333333"))

        // Экран показывает подтверждение, а не поле: второй запрос был бы
        // сменой, о которой никто не просил.
        assertEquals(1, repository.changeCalls.size)
        assertTrue(viewModel.state.value.done)
    }
}
