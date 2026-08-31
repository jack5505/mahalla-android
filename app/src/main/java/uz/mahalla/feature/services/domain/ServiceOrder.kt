package uz.mahalla.feature.services.domain

/**
 * Форма заказа услуги — клиентская половина issue #71.
 *
 * Поля ровно те, что принимает бэкенд (`POST walkin/send`, контроллер
 * `walk-in`): `placeId` берётся из маршрута, а человек заполняет имя и
 * название услуги. Ни времени, ни комментария в контракте нет — поле, которое
 * никуда не уедет, на форме хуже его отсутствия.
 *
 * [customerName] предзаполняется именем аккаунта, но остаётся редактируемым:
 * услугу заказывают и для другого человека («для сына»), а мастер видит
 * именно эту подпись в своей очереди.
 */
data class ServiceOrderForm(
    val customerName: String = "",
    val serviceName: String = "",
)

/** Что не так с формой. Каждая ошибка привязана к своему полю на экране. */
sealed interface ServiceOrderError {
    data object NameRequired : ServiceOrderError
    data class NameTooLong(val maxLength: Int) : ServiceOrderError

    data object ServiceRequired : ServiceOrderError
    data class ServiceTooLong(val maxLength: Int) : ServiceOrderError
}

/**
 * Валидация формы заказа — чистая функция, как [CheckoutValidator]
 * [uz.mahalla.feature.food.domain.CheckoutValidator] в еде.
 *
 * Проверяется всё сразу: подсвечивать ошибки по одной значит гонять человека
 * по форме кругами.
 *
 * Название услуги бэкенд объявляет **необязательным**, а форма требует его
 * осознанно: заявка «Азиз, ???» мастеру бесполезна — он всё равно перезвонит
 * спрашивать, что нужно, и смысл заочной очереди теряется. Ограничение
 * клиентское, поэтому оно мягче серверного и живёт в одном месте.
 */
object ServiceOrderValidator {

    /** Столько же принимает подпись в очереди на стороне мастера. */
    const val MAX_NAME_LENGTH = 100

    const val MAX_SERVICE_LENGTH = 120

    fun validate(form: ServiceOrderForm): List<ServiceOrderError> = buildList {
        val name = form.customerName.trim()
        when {
            name.isEmpty() -> add(ServiceOrderError.NameRequired)
            name.length > MAX_NAME_LENGTH -> add(ServiceOrderError.NameTooLong(MAX_NAME_LENGTH))
        }

        val service = form.serviceName.trim()
        when {
            service.isEmpty() -> add(ServiceOrderError.ServiceRequired)
            service.length > MAX_SERVICE_LENGTH ->
                add(ServiceOrderError.ServiceTooLong(MAX_SERVICE_LENGTH))
        }
    }

    fun canSubmit(form: ServiceOrderForm): Boolean = validate(form).isEmpty()
}
