package uz.mahalla.feature.freelancer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator

/**
 * Анкета мастера (issue #71): правила формы взяты из схемы
 * `FreelancerCreateRequest`, а не придуманы, — поэтому проверяются здесь, а не
 * ответом сервера.
 */
class FreelancerProfileFormTest {

    private val phoneValidator = PhoneNumberValidator()

    @Test
    fun `filled form has no errors`() {
        val errors = validate(
            FreelancerProfileForm(
                name = "Aziz Karimov",
                profession = "Santexnik",
                city = "Toshkent",
                phoneDigits = "901234567",
                hourlyRateText = "80000",
                experienceYearsText = "7",
            ),
        )

        assertTrue(errors.toString(), errors.isEmpty())
    }

    /** Обязательны ровно два поля — `required: [name, profession]` в схеме. */
    @Test
    fun `name and profession are required`() {
        val errors = validate(FreelancerProfileForm())

        assertEquals(
            listOf(
                FreelancerProfileFormError.NameRequired,
                FreelancerProfileFormError.ProfessionRequired,
            ),
            errors,
        )
    }

    /** Пробелы — не имя: форма обрезается до проверки, а не после неё. */
    @Test
    fun `blank name is required too`() {
        val errors = validate(FreelancerProfileForm(name = "   ", profession = "Santexnik"))

        assertEquals(listOf(FreelancerProfileFormError.NameRequired), errors)
    }

    @Test
    fun `single letter name is too short`() {
        val errors = validate(FreelancerProfileForm(name = "A", profession = "Santexnik"))

        assertEquals(
            listOf(FreelancerProfileFormError.NameTooShort(FreelancerProfileForm.MIN_NAME_LENGTH)),
            errors,
        )
    }

    /** Длины — из схемы: 200 / 100 / 100 / 2000. */
    @Test
    fun `too long fields are reported with their limits`() {
        val errors = validate(
            FreelancerProfileForm(
                name = "a".repeat(FreelancerProfileForm.MAX_NAME_LENGTH + 1),
                profession = "b".repeat(FreelancerProfileForm.MAX_PROFESSION_LENGTH + 1),
                city = "c".repeat(FreelancerProfileForm.MAX_CITY_LENGTH + 1),
                bio = "d".repeat(FreelancerProfileForm.MAX_BIO_LENGTH + 1),
            ),
        )

        assertEquals(
            listOf(
                FreelancerProfileFormError.NameTooLong(FreelancerProfileForm.MAX_NAME_LENGTH),
                FreelancerProfileFormError.ProfessionTooLong(
                    FreelancerProfileForm.MAX_PROFESSION_LENGTH,
                ),
                FreelancerProfileFormError.CityTooLong(FreelancerProfileForm.MAX_CITY_LENGTH),
                FreelancerProfileFormError.BioTooLong(FreelancerProfileForm.MAX_BIO_LENGTH),
            ),
            errors,
        )
    }

    /** Телефона в `required` нет: анкета без него — законная. */
    @Test
    fun `empty phone is allowed and a broken one is not`() {
        val filled = FreelancerProfileForm(name = "Aziz", profession = "Santexnik")

        assertTrue(validate(filled).isEmpty())
        assertEquals(
            listOf(FreelancerProfileFormError.PhoneInvalid),
            validate(filled.copy(phoneDigits = "901234")),
        )
    }

    /**
     * Ставка и стаж — `int32` у бэкенда. Набор, который в него не влезает,
     * молча превратился бы в «не указано» и затёр бы сохранённое значение.
     */
    @Test
    fun `numbers beyond int32 are errors and not silent zeroes`() {
        val form = FreelancerProfileForm(
            name = "Aziz",
            profession = "Santexnik",
            hourlyRateText = "99999999999",
            experienceYearsText = "99999999999",
        )

        assertNull(form.hourlyRate)
        assertNull(form.experienceYears)
        assertEquals(
            listOf(
                FreelancerProfileFormError.HourlyRateInvalid,
                FreelancerProfileFormError.ExperienceInvalid,
            ),
            validate(form),
        )
    }

    /** Пустые числа — это «не указано», а не ноль и не ошибка. */
    @Test
    fun `empty numbers are absent`() {
        val form = FreelancerProfileForm(name = "Aziz", profession = "Santexnik")

        assertNull(form.hourlyRate)
        assertNull(form.experienceYears)
        assertTrue(validate(form).isEmpty())
    }

    /**
     * Правка открывается заполненной: сохранённая анкета уходит той же ручкой
     * целиком, и пустые поля затёрли бы серверные.
     */
    @Test
    fun `saved profile becomes the same form`() {
        val form = FreelancerProfileForm.of(
            freelancer = Freelancer(
                id = "f-1",
                name = "Aziz Karimov",
                profession = "Santexnik",
                bio = "Tajribali",
                city = "Toshkent",
                phone = "+998901234567",
                hourlyRateSum = 80_000,
                experienceYears = 7,
            ),
            phoneDigits = "901234567",
        )

        assertEquals("Aziz Karimov", form.name)
        assertEquals("Santexnik", form.profession)
        assertEquals("Tajribali", form.bio)
        assertEquals("Toshkent", form.city)
        assertEquals("901234567", form.phoneDigits)
        assertEquals("80000", form.hourlyRateText)
        assertEquals("7", form.experienceYearsText)
    }

    /** Ставки нет — поле пустое, а не «0»: ноль читался бы как «работаю даром». */
    @Test
    fun `missing rate stays an empty field`() {
        val form = FreelancerProfileForm.of(
            freelancer = Freelancer(id = "f-1", name = "Aziz"),
            phoneDigits = "",
        )

        assertEquals("", form.hourlyRateText)
        assertEquals("", form.experienceYearsText)
    }

    private fun validate(form: FreelancerProfileForm) =
        FreelancerProfileFormValidator.validate(form, phoneValidator::isValid)
}
