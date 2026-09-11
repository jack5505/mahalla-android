package uz.mahalla.feature.freelancer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Анкета мастера и черновик услуги (issue #190): проверки до сети. */
class FreelancerCabinetTest {

    private val alwaysValidPhone: (String) -> Boolean = { true }

    @Test
    fun `valid anketa has no errors`() {
        val form = FreelancerAnketaForm(name = "Aziz", profession = "Santexnik")

        assertTrue(FreelancerAnketaFormValidator.validate(form, alwaysValidPhone).isEmpty())
    }

    @Test
    fun `blank name is required`() {
        val form = FreelancerAnketaForm(name = "  ", profession = "Santexnik")

        assertEquals(
            listOf(FreelancerAnketaFormError.NameRequired),
            FreelancerAnketaFormValidator.validate(form, alwaysValidPhone),
        )
    }

    @Test
    fun `single letter name is too short`() {
        val form = FreelancerAnketaForm(name = "A", profession = "Santexnik")

        assertEquals(
            listOf(FreelancerAnketaFormError.NameTooShort(FreelancerAnketaForm.MIN_NAME_LENGTH)),
            FreelancerAnketaFormValidator.validate(form, alwaysValidPhone),
        )
    }

    @Test
    fun `blank profession is required`() {
        val form = FreelancerAnketaForm(name = "Aziz", profession = " ")

        assertEquals(
            listOf(FreelancerAnketaFormError.ProfessionRequired),
            FreelancerAnketaFormValidator.validate(form, alwaysValidPhone),
        )
    }

    @Test
    fun `too long bio is rejected`() {
        val form = FreelancerAnketaForm(
            name = "Aziz",
            profession = "Santexnik",
            bio = "a".repeat(FreelancerAnketaForm.MAX_BIO_LENGTH + 1),
        )

        assertEquals(
            listOf(FreelancerAnketaFormError.BioTooLong(FreelancerAnketaForm.MAX_BIO_LENGTH)),
            FreelancerAnketaFormValidator.validate(form, alwaysValidPhone),
        )
    }

    /** Телефон необязателен: мастер и так вошёл по своему номеру. */
    @Test
    fun `blank phone is not validated`() {
        val form = FreelancerAnketaForm(name = "Aziz", profession = "Santexnik", phoneDigits = "")

        assertTrue(FreelancerAnketaFormValidator.validate(form) { false }.isEmpty())
    }

    @Test
    fun `filled invalid phone is rejected`() {
        val form = FreelancerAnketaForm(
            name = "Aziz",
            profession = "Santexnik",
            phoneDigits = "123",
        )

        assertEquals(
            listOf(FreelancerAnketaFormError.PhoneInvalid),
            FreelancerAnketaFormValidator.validate(form) { false },
        )
    }

    @Test
    fun `service draft parses price and duration from digits only`() {
        val draft = FreelancerServiceDraft(priceText = "80 000", durationText = "60 min")

        assertEquals(80_000L, draft.priceSum)
        assertEquals(60, draft.durationMinutes)
    }

    @Test
    fun `zero or blank price and duration are not provided`() {
        val draft = FreelancerServiceDraft(priceText = "0", durationText = "")

        assertNull(draft.priceSum)
        assertNull(draft.durationMinutes)
    }

    @Test
    fun `draft from an existing service round trips the values`() {
        val service = FreelancerCabinetService(
            id = "s-1",
            title = "Kran",
            description = "Almashtirish",
            priceSum = 80_000,
            durationMinutes = 60,
            isActive = false,
        )

        val draft = FreelancerServiceDraft.from(service)

        assertEquals("Kran", draft.title)
        assertEquals("Almashtirish", draft.description)
        assertEquals("80000", draft.priceText)
        assertEquals("60", draft.durationText)
        assertEquals(false, draft.isActive)
    }

    @Test
    fun `valid service draft has no errors`() {
        val draft = FreelancerServiceDraft(title = "Kran", priceText = "80000", durationText = "60")

        assertTrue(FreelancerServiceFormValidator.validate(draft).isEmpty())
    }

    @Test
    fun `blank title price and duration are all reported at once`() {
        val errors = FreelancerServiceFormValidator.validate(FreelancerServiceDraft())

        assertEquals(
            setOf(
                FreelancerServiceFormError.TitleRequired,
                FreelancerServiceFormError.PriceRequired,
                FreelancerServiceFormError.DurationRequired,
            ),
            errors.toSet(),
        )
    }

    @Test
    fun `too long title is rejected`() {
        val draft = FreelancerServiceDraft(
            title = "a".repeat(FreelancerServiceDraft.MAX_TITLE_LENGTH + 1),
            priceText = "80000",
            durationText = "60",
        )

        assertEquals(
            listOf(FreelancerServiceFormError.TitleTooLong(FreelancerServiceDraft.MAX_TITLE_LENGTH)),
            FreelancerServiceFormValidator.validate(draft),
        )
    }
}
