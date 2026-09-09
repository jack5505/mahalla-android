package uz.mahalla.feature.freelancer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.booking.domain.BarberService

/**
 * Форма выставления услуги (issue #71). Правила — из схемы `ServiceRequest`:
 * обязательны название и цена, длины 200 / 2000.
 */
class FreelancerServiceFormTest {

    @Test
    fun `filled service has no errors`() {
        val errors = FreelancerServiceFormValidator.validate(
            FreelancerServiceForm(
                title = "Kran almashtirish",
                description = "Materiallar mijoznikidan",
                priceText = "150000",
                durationText = "60",
            ),
        )

        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `title and price are required`() {
        val errors = FreelancerServiceFormValidator.validate(FreelancerServiceForm())

        assertEquals(
            listOf(
                FreelancerServiceFormError.TitleRequired,
                FreelancerServiceFormError.PriceRequired,
            ),
            errors,
        )
    }

    @Test
    fun `too long title and description are reported with their limits`() {
        val errors = FreelancerServiceFormValidator.validate(
            FreelancerServiceForm(
                title = "a".repeat(FreelancerServiceForm.MAX_TITLE_LENGTH + 1),
                description = "b".repeat(FreelancerServiceForm.MAX_DESCRIPTION_LENGTH + 1),
                priceText = "1000",
            ),
        )

        assertEquals(
            listOf(
                FreelancerServiceFormError.TitleTooLong(FreelancerServiceForm.MAX_TITLE_LENGTH),
                FreelancerServiceFormError.DescriptionTooLong(
                    FreelancerServiceForm.MAX_DESCRIPTION_LENGTH,
                ),
            ),
            errors,
        )
    }

    /**
     * Цена — `int64`. Набор длиннее него превратился бы в «не указано», а
     * услуга ушла бы на сервер бесплатной.
     */
    @Test
    fun `price beyond int64 is an error`() {
        val form = FreelancerServiceForm(title = "Kran", priceText = "9".repeat(30))

        assertNull(form.price)
        assertEquals(
            listOf(FreelancerServiceFormError.PriceInvalid),
            FreelancerServiceFormValidator.validate(form),
        )
    }

    /** Ноль ценой разрешён (`@Min(0)`): «бесплатный осмотр» — это предложение. */
    @Test
    fun `zero price is allowed`() {
        val form = FreelancerServiceForm(title = "Ko'rik", priceText = "0")

        assertEquals(0L, form.price)
        assertTrue(FreelancerServiceFormValidator.validate(form).isEmpty())
    }

    /** Длительность необязательна, но нечитаемая — ошибка, а не молчание. */
    @Test
    fun `duration is optional and validated when filled`() {
        val form = FreelancerServiceForm(title = "Kran", priceText = "1000")

        assertNull(form.duration)
        assertTrue(FreelancerServiceFormValidator.validate(form).isEmpty())
        assertEquals(
            listOf(FreelancerServiceFormError.DurationInvalid),
            FreelancerServiceFormValidator.validate(form.copy(durationText = "9".repeat(20))),
        )
    }

    /**
     * Правка — та же форма, но с `id`: от него зависит только метод запроса
     * (`POST` против `PUT`).
     */
    @Test
    fun `existing service becomes an editable form`() {
        val form = FreelancerServiceForm.of(
            BarberService(
                id = "s-1",
                title = "Kran almashtirish",
                description = "Materiallar mijoznikidan",
                priceSum = 150_000,
                durationMinutes = 60,
            ),
        )

        assertFalse(form.isNew)
        assertEquals("s-1", form.id)
        assertEquals("Kran almashtirish", form.title)
        assertEquals("150000", form.priceText)
        assertEquals("60", form.durationText)
        assertTrue(FreelancerServiceForm().isNew)
    }

    /**
     * У бесплатной услуги поле цены открывается явным нулём: пустое не дало бы
     * поправить ни описание, ни длительность — валидатор потребовал бы сперва
     * набрать «0» руками.
     */
    @Test
    fun `free service opens with an explicit zero`() {
        val form = FreelancerServiceForm.of(BarberService(id = "s-1", title = "Ko'rik"))

        assertEquals("0", form.priceText)
        assertTrue(FreelancerServiceFormValidator.validate(form).isEmpty())
    }
}
