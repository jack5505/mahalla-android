package uz.mahalla.feature.services.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма заказа услуги (issue #71): что уходит на сервер, а что нет.
 *
 * Валидация — чистая функция, поэтому она проверяется без Android и без
 * ViewModel: экран лишь показывает то, что здесь посчитано.
 */
class ServiceOrderValidatorTest {

    @Test
    fun `empty form reports both fields at once`() {
        val errors = ServiceOrderValidator.validate(ServiceOrderForm())

        // Обе ошибки сразу: подсвечивать их по одной значит гонять человека по
        // форме кругами.
        assertEquals(
            listOf(ServiceOrderError.NameRequired, ServiceOrderError.ServiceRequired),
            errors,
        )
        assertFalse(ServiceOrderValidator.canSubmit(ServiceOrderForm()))
    }

    @Test
    fun `whitespace is not a name`() {
        val form = ServiceOrderForm(customerName = "   ", serviceName = "Soch olish")

        assertEquals(listOf(ServiceOrderError.NameRequired), ServiceOrderValidator.validate(form))
    }

    @Test
    fun `service is required although the backend calls it optional`() {
        // Заявка «Азиз, ???» мастеру бесполезна — он всё равно перезвонит
        // спрашивать, и смысл заочной очереди теряется.
        val form = ServiceOrderForm(customerName = "Aziz")

        assertEquals(listOf(ServiceOrderError.ServiceRequired), ServiceOrderValidator.validate(form))
    }

    @Test
    fun `filled form is submittable`() {
        val form = ServiceOrderForm(customerName = "Aziz", serviceName = "Soch olish")

        assertTrue(ServiceOrderValidator.canSubmit(form))
    }

    @Test
    fun `too long values are reported with their limit`() {
        val form = ServiceOrderForm(
            customerName = "a".repeat(ServiceOrderValidator.MAX_NAME_LENGTH + 1),
            serviceName = "b".repeat(ServiceOrderValidator.MAX_SERVICE_LENGTH + 1),
        )

        assertEquals(
            listOf(
                ServiceOrderError.NameTooLong(ServiceOrderValidator.MAX_NAME_LENGTH),
                ServiceOrderError.ServiceTooLong(ServiceOrderValidator.MAX_SERVICE_LENGTH),
            ),
            ServiceOrderValidator.validate(form),
        )
    }

    @Test
    fun `value exactly at the limit passes`() {
        val form = ServiceOrderForm(
            customerName = "a".repeat(ServiceOrderValidator.MAX_NAME_LENGTH),
            serviceName = "b".repeat(ServiceOrderValidator.MAX_SERVICE_LENGTH),
        )

        assertTrue(ServiceOrderValidator.canSubmit(form))
    }
}
