package uz.mahalla.feature.services.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator

/** Форма выставления услуги (issue #71). */
class ServiceOfferValidatorTest {

    private val phone = PhoneNumberValidator()

    private val filled = ServiceOfferForm(
        name = "Jahongir",
        profession = "Sartarosh",
        city = "Toshkent",
    )

    @Test
    fun `empty form reports the three fields the catalog searches by`() {
        val errors = ServiceOfferValidator.validate(ServiceOfferForm(), phone)

        assertEquals(
            listOf(
                ServiceOfferError.NameRequired,
                ServiceOfferError.ProfessionRequired,
                ServiceOfferError.CityRequired,
            ),
            errors,
        )
    }

    @Test
    fun `name profession and city are enough`() {
        // Цена, стаж и телефон необязательны: «договоримся» — нормальный ответ,
        // а номер у аккаунта уже есть.
        assertTrue(ServiceOfferValidator.canSubmit(filled, phone))
    }

    @Test
    fun `incomplete phone is rejected but empty one is fine`() {
        assertTrue(ServiceOfferValidator.canSubmit(filled.copy(phoneDigits = ""), phone))
        assertEquals(
            listOf(ServiceOfferError.PhoneInvalid),
            ServiceOfferValidator.validate(filled.copy(phoneDigits = "9012345"), phone),
        )
        assertTrue(
            ServiceOfferValidator.canSubmit(filled.copy(phoneDigits = "901234567"), phone),
        )
    }

    @Test
    fun `rate must be a positive number within the int32 of the backend`() {
        assertNull(ServiceOfferValidator.rateSum(filled))
        assertEquals(80_000L, ServiceOfferValidator.rateSum(filled.copy(hourlyRate = "80000")))

        assertEquals(
            listOf(ServiceOfferError.RateInvalid(ServiceOfferValidator.MAX_RATE_SUM)),
            ServiceOfferValidator.validate(filled.copy(hourlyRate = "0"), phone),
        )
        assertEquals(
            listOf(ServiceOfferError.RateInvalid(ServiceOfferValidator.MAX_RATE_SUM)),
            ServiceOfferValidator.validate(
                filled.copy(hourlyRate = "${ServiceOfferValidator.MAX_RATE_SUM + 1}"),
                phone,
            ),
        )
    }

    @Test
    fun `experience is bounded but zero years is a valid answer`() {
        assertTrue(ServiceOfferValidator.canSubmit(filled.copy(experienceYears = "0"), phone))
        assertEquals(0, ServiceOfferValidator.experienceYears(filled.copy(experienceYears = "0")))
        assertEquals(
            listOf(
                ServiceOfferError.ExperienceInvalid(ServiceOfferValidator.MAX_EXPERIENCE_YEARS),
            ),
            ServiceOfferValidator.validate(filled.copy(experienceYears = "300"), phone),
        )
    }

    @Test
    fun `long values are reported with their limit`() {
        val form = filled.copy(
            name = "a".repeat(ServiceOfferValidator.MAX_NAME_LENGTH + 1),
            bio = "b".repeat(ServiceOfferValidator.MAX_BIO_LENGTH + 1),
        )

        assertEquals(
            listOf(
                ServiceOfferError.NameTooLong(ServiceOfferValidator.MAX_NAME_LENGTH),
                ServiceOfferError.BioTooLong(ServiceOfferValidator.MAX_BIO_LENGTH),
            ),
            ServiceOfferValidator.validate(form, phone),
        )
    }

    @Test
    fun `form is filled from the offer the server knows`() {
        val offer = ServiceOffer(
            name = "Jahongir",
            profession = "Sartarosh",
            bio = "10 yil tajriba",
            city = "Toshkent",
            phone = "+998 90 123 45 67",
            hourlyRateSum = 80_000,
            experienceYears = 10,
        )

        val form = ServiceOfferForm.of(offer, phone)

        // Номер приходит форматированным, а поле работает с девятью цифрами.
        assertEquals("901234567", form.phoneDigits)
        assertEquals("80000", form.hourlyRate)
        assertEquals("10", form.experienceYears)
        assertTrue(ServiceOfferValidator.canSubmit(form, phone))
    }

    @Test
    fun `zero rate and zero experience come back as empty fields`() {
        // Ноль в анкете значит «не указано»: показывать его числом — обещать
        // бесплатную работу и нулевой стаж.
        val form = ServiceOfferForm.of(
            ServiceOffer(name = "A", profession = "B", city = "C", hourlyRateSum = 0),
            phone,
        )

        assertEquals("", form.hourlyRate)
        assertEquals("", form.experienceYears)
        assertFalse(form.name.isEmpty())
    }
}
