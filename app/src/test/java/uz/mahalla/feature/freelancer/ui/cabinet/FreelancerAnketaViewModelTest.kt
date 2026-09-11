package uz.mahalla.feature.freelancer.ui.cabinet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.testutil.FakeFreelancerCabinetRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Анкета мастера (issue #190): создание, правка, проверка полей. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreelancerAnketaViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerCabinetRepository()
    private val phoneValidator = PhoneNumberValidator()

    private fun viewModel() = FreelancerAnketaViewModel(repository, phoneValidator)

    @Test
    fun `empty profile leaves the form blank and not editing`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(null)

        val viewModel = viewModel()
        runCurrent()

        assertFalse(viewModel.state.value.editing)
        assertEquals("", viewModel.state.value.form.name)
    }

    /** Правка — та же форма, предзаполненная тем, что уже приезжало в кабинет. */
    @Test
    fun `existing profile prefills the form for editing`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(
            Freelancer(
                id = "f-1",
                name = "Aziz Karimov",
                profession = "Santexnik",
                bio = "Tajribali",
                city = "Toshkent",
                phone = "+998901234567",
                hourlyRateSum = 50_000,
                experienceYears = 7,
            ),
        )

        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.editing)
        assertEquals("Aziz Karimov", state.form.name)
        assertEquals("Santexnik", state.form.profession)
        assertEquals("901234567", state.form.phoneDigits)
        assertEquals("50000", state.form.hourlyRateText)
        assertEquals("7", state.form.experienceYearsText)
    }

    @Test
    fun `submit without a name is rejected before the network`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(FreelancerAnketaEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(FreelancerAnketaEvent.SubmitClicked)
        runCurrent()

        assertTrue(viewModel.state.value.validationShown)
        assertTrue(repository.submittedForms.isEmpty())
    }

    @Test
    fun `valid form is submitted and the effect fires`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        val effects = mutableListOf<FreelancerAnketaEffect>()
        val job = launch { viewModel.effects.collect { effects += it } }

        viewModel.onEvent(FreelancerAnketaEvent.NameChanged("Aziz"))
        viewModel.onEvent(FreelancerAnketaEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(FreelancerAnketaEvent.SubmitClicked)
        runCurrent()

        assertEquals(1, repository.submittedForms.size)
        assertEquals("Aziz", repository.submittedForms.single().name)
        assertNotNull((effects.single() as FreelancerAnketaEffect.Submitted).profile)
        job.cancel()
    }

    @Test
    fun `server failure is shown and the form stays filled`() = runTest(mainDispatcherRule.dispatcher) {
        repository.submitProfileResult = ApiResult.Failure(ApiError.Unauthorized)
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(FreelancerAnketaEvent.NameChanged("Aziz"))
        viewModel.onEvent(FreelancerAnketaEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(FreelancerAnketaEvent.SubmitClicked)
        runCurrent()

        assertEquals(ApiError.Unauthorized, viewModel.state.value.submitError?.error)
        assertEquals("Aziz", viewModel.state.value.form.name)
        assertFalse(viewModel.state.value.submitting)
    }
}
