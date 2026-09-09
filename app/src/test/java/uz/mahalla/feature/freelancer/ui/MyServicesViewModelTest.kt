package uz.mahalla.feature.freelancer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.ui.me.MyServicesEvent
import uz.mahalla.feature.freelancer.ui.me.MyServicesViewModel
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.testutil.FakeFreelancerRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.MainDispatcherRule

/**
 * «Мои услуги» — кабинет мастера (issue #71): анкета, переключатель «принимаю
 * заказы» и выставление услуг.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyServicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerRepository()

    private val profileStore = FakeUserProfileStore(
        UserProfile(phone = "+998901234567", fullName = "Aziz Karimov"),
    )

    /**
     * Анкеты нет — форма открыта сразу и заполнена тем, что приложение уже
     * знает: набирать имя и телефон заново — способ получить брошенную форму.
     */
    @Test
    fun `absent profile opens a prefilled form`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.hasNoProfile)
        assertTrue(state.formOpen)
        assertEquals("Aziz Karimov", state.form.name)
        assertEquals("901234567", state.form.phoneDigits)
        // Услуги выставлять некуда: их ручка ходит по `id` анкеты.
        assertFalse(state.canManageServices)
        assertTrue(repository.requestedMyServices.isEmpty())
    }

    /** Анкета есть — экран показывает её, а не форму, и грузит услуги. */
    @Test
    fun `existing profile shows the card and loads services`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.myProfileResult = ApiResult.Success(freelancer())
            repository.myServicesResult = ApiResult.Success(listOf(service("s-1")))

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertFalse(state.formOpen)
            assertEquals("f-1", state.freelancer?.id)
            assertEquals(listOf("f-1"), repository.requestedMyServices)
            assertEquals(
                listOf("s-1"),
                (state.services as ScreenState.Content).data.map { it.id },
            )
        }

    /** Незаполненная анкета в сеть не уходит: сервер ответил бы тем же. */
    @Test
    fun `incomplete profile is not sent`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MyServicesEvent.NameChanged(""))
        viewModel.onEvent(MyServicesEvent.SaveProfileClicked)
        runCurrent()

        assertTrue(repository.savedProfiles.isEmpty())
        assertTrue(viewModel.state.value.formValidationShown)
        assertTrue(viewModel.state.value.visibleFormErrors.isNotEmpty())
    }

    /**
     * После сохранения анкета **перечитывается**: ответ `POST freelancers/me`
     * может приехать без `id`, а по нему грузятся услуги.
     */
    @Test
    fun `saved profile is re-read from the server`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResults += ApiResult.Success(null)
        repository.myProfileResults += ApiResult.Success(freelancer())

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(MyServicesEvent.SaveProfileClicked)
        runCurrent()

        assertEquals(1, repository.savedProfiles.size)
        assertEquals("Santexnik", repository.savedProfiles.first().profession)
        assertEquals(2, repository.myProfileRequests)
        val state = viewModel.state.value
        assertFalse(state.formOpen)
        assertEquals("f-1", state.freelancer?.id)
    }

    /** Отказ сервера форму не закрывает и набранное не теряет (issue #34). */
    @Test
    fun `rejected save keeps the form and the text`() = runTest(mainDispatcherRule.dispatcher) {
        repository.saveProfileResult = ApiResult.Failure(ApiError.Business("PROFILE_EXISTS"))

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(MyServicesEvent.SaveProfileClicked)
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.formOpen)
        assertEquals("Santexnik", state.form.profession)
        assertEquals(ApiError.Business("PROFILE_EXISTS"), state.profileFailure?.error)
        assertFalse(state.savingProfile)
    }

    /** Правка стирает прошлый отказ: он был про другие данные. */
    @Test
    fun `editing clears the previous failure`() = runTest(mainDispatcherRule.dispatcher) {
        repository.saveProfileResult = ApiResult.Failure(ApiError.NoConnection)

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.ProfessionChanged("Santexnik"))
        viewModel.onEvent(MyServicesEvent.SaveProfileClicked)
        runCurrent()
        viewModel.onEvent(MyServicesEvent.ProfessionChanged("Elektrik"))

        assertNull(viewModel.state.value.profileFailure)
    }

    /** «Отмена» возвращает поля к сохранённому: правку никто не подтверждал. */
    @Test
    fun `cancel restores the saved profile`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Success(freelancer())

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.EditProfileClicked)
        viewModel.onEvent(MyServicesEvent.ProfessionChanged("Elektrik"))
        viewModel.onEvent(MyServicesEvent.CancelProfileEditClicked)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.formOpen)
        assertEquals("Santexnik", state.form.profession)
        assertTrue(repository.savedProfiles.isEmpty())
    }

    /**
     * Значение переключателя задаёт сервер (ручка инвертирует флаг сама),
     * поэтому оно перечитывается, а не считается на клиенте.
     */
    @Test
    fun `availability is re-read after the toggle`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResults += ApiResult.Success(freelancer(available = true))
        repository.myProfileResults += ApiResult.Success(freelancer(available = false))

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.AvailabilityToggled)
        runCurrent()

        assertEquals(1, repository.toggleCount)
        assertEquals(2, repository.myProfileRequests)
        assertFalse(viewModel.state.value.freelancer?.isAvailable ?: true)
    }

    /**
     * Перечитать не вышло — остаётся ожидаемое значение: сервер переключение
     * уже принял, и показывать старое было бы прямой ложью.
     */
    @Test
    fun `failed re-read keeps the expected availability`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.myProfileResults += ApiResult.Success(freelancer(available = true))
            repository.myProfileResults += ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(MyServicesEvent.AvailabilityToggled)
            runCurrent()

            assertFalse(viewModel.state.value.freelancer?.isAvailable ?: true)
        }

    /** Отказ самого переключения значение не меняет. */
    @Test
    fun `rejected toggle keeps the value and shows the failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.myProfileResult = ApiResult.Success(freelancer(available = true))
            repository.toggleResult = ApiResult.Failure(ApiError.Forbidden)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(MyServicesEvent.AvailabilityToggled)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.freelancer?.isAvailable ?: false)
            assertEquals(ApiError.Forbidden, state.availabilityFailure?.error)
        }

    /** Выставленная услуга закрывает форму, а список перечитывается. */
    @Test
    fun `new service is saved and the list is re-read`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Success(freelancer())

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.AddServiceClicked)
        viewModel.onEvent(MyServicesEvent.ServiceTitleChanged("Kran"))
        viewModel.onEvent(MyServicesEvent.ServicePriceChanged("150000"))
        viewModel.onEvent(MyServicesEvent.SaveServiceClicked)
        runCurrent()

        val saved = repository.savedServices.single()
        assertNull(saved.id)
        assertEquals("Kran", saved.title)
        assertEquals(150_000L, saved.price)
        assertNull(viewModel.state.value.serviceForm)
        assertEquals(listOf("f-1", "f-1"), repository.requestedMyServices)
    }

    /** Правка уходит с `id` — иначе на сервере появился бы дубль. */
    @Test
    fun `editing a service keeps its id`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Success(freelancer())
        repository.myServicesResult = ApiResult.Success(listOf(service("s-1")))

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.EditServiceClicked("s-1"))
        viewModel.onEvent(MyServicesEvent.ServicePriceChanged("170000"))
        viewModel.onEvent(MyServicesEvent.SaveServiceClicked)
        runCurrent()

        val saved = repository.savedServices.single()
        assertEquals("s-1", saved.id)
        assertEquals(170_000L, saved.price)
    }

    /** Цифры чистятся на вводе: буква в цене — опечатка, а не намерение. */
    @Test
    fun `price keeps only digits`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Success(freelancer())

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.AddServiceClicked)
        viewModel.onEvent(MyServicesEvent.ServicePriceChanged("150 000 so'm"))

        assertEquals("150000", viewModel.state.value.serviceForm?.priceText)
    }

    /** Услуга без названия в сеть не уходит — показываются замечания формы. */
    @Test
    fun `incomplete service is not sent`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Success(freelancer())

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyServicesEvent.AddServiceClicked)
        viewModel.onEvent(MyServicesEvent.SaveServiceClicked)
        runCurrent()

        assertTrue(repository.savedServices.isEmpty())
        assertTrue(viewModel.state.value.serviceValidationShown)
        assertTrue(viewModel.state.value.visibleServiceErrors.isNotEmpty())
    }

    /** Снятие услуги спрашивают: вернуть её нечем. */
    @Test
    fun `delete asks first and then re-reads the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.myProfileResult = ApiResult.Success(freelancer())
            repository.myServicesResult = ApiResult.Success(listOf(service("s-1")))

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(MyServicesEvent.DeleteServiceClicked("s-1"))
            runCurrent()

            assertEquals("s-1", viewModel.state.value.confirmDelete?.id)
            assertTrue(repository.deletedServices.isEmpty())

            viewModel.onEvent(MyServicesEvent.DeleteConfirmed)
            runCurrent()

            assertEquals(listOf("s-1"), repository.deletedServices)
            assertNull(viewModel.state.value.confirmDelete)
            assertEquals(listOf("f-1", "f-1"), repository.requestedMyServices)
        }

    /** Отказ анкеты — экран ошибки с «повторить», а не пустая форма. */
    @Test
    fun `profile failure is an error state with retry`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myProfileResult = ApiResult.Failure(ApiError.NoConnection)

        val viewModel = viewModel()
        runCurrent()

        assertEquals(
            ApiError.NoConnection,
            (viewModel.state.value.profile as ScreenState.Error).error,
        )

        repository.myProfileResult = ApiResult.Success(freelancer())
        viewModel.onEvent(MyServicesEvent.Retry)
        runCurrent()

        assertEquals("f-1", viewModel.state.value.freelancer?.id)
    }

    private fun viewModel() = MyServicesViewModel(
        repository = repository,
        profileStore = profileStore,
        phoneValidator = PhoneNumberValidator(),
    )

    private fun freelancer(available: Boolean = true) = Freelancer(
        id = "f-1",
        name = "Aziz Karimov",
        profession = "Santexnik",
        city = "Toshkent",
        phone = "+998901234567",
        hourlyRateSum = 80_000,
        experienceYears = 7,
        isAvailable = available,
    )

    private fun service(id: String) = BarberService(
        id = id,
        title = "Kran almashtirish",
        priceSum = 150_000,
        durationMinutes = 60,
    )
}
