package uz.mahalla.feature.role.domain

import uz.mahalla.feature.onboarding.domain.City

/**
 * Анкета покупателя (issue #84): как зовут, где живёт и куда везти заказ.
 *
 * Хранится локально. Профиля пользователя у бэкенда нет вовсе — ни
 * `GET /users/me`, ни `PUT`: имя приезжает только в ответе на вход (issue
 * #61), и отправить его серверу нечем. Поэтому анкета — не «регистрация», а
 * то, чем приложение пользуется само: имя показывается в шапке профиля, город
 * подставляется в координаты запросов (`RequestLocationProvider`), а адрес —
 * в оформление заказа, где его иначе набирают заново каждый раз.
 *
 * Поля хранятся как их набрал человек, без обрезки: молча укоротить имя
 * значит не объяснить, куда делись символы. Границы проверяет
 * [CustomerFormValidator], а обрезает — только [trimmed] перед сохранением.
 */
data class CustomerForm(
    val fullName: String = "",
    val city: City? = null,
    val address: String = "",
) {

    /** Что уходит в хранилище: без краевых пробелов, но без обрезки длины. */
    fun trimmed(): CustomerForm = copy(
        fullName = fullName.trim(),
        address = address.trim(),
    )

    /** Анкету не заполняли: пустая форма и сохранения не стоит. */
    val isEmpty: Boolean
        get() = fullName.isBlank() && address.isBlank() && city == null

    companion object {
        /** Длиннее имени не бывает; ограничение наше, бэкенд имя не хранит. */
        const val MAX_NAME_LENGTH = 120

        /** Столько же принимает адрес доставки у бэкенда (`@Size(max = 500)`). */
        const val MAX_ADDRESS_LENGTH = 500
    }
}

/** Что не так с анкетой покупателя. Каждая ошибка привязана к своему полю. */
sealed interface CustomerFormError {
    data object NameRequired : CustomerFormError
    data class NameTooLong(val max: Int) : CustomerFormError
    data object CityRequired : CustomerFormError
    data class AddressTooLong(val max: Int) : CustomerFormError
}

/**
 * Проверка анкеты покупателя.
 *
 * Ошибки возвращаются **все сразу**: человек заполняет форму целиком, и
 * показывать замечания по одному — это заставить его нажимать «сохранить»
 * четыре раза.
 *
 * Адрес необязателен: покупатель может забирать заказы сам, и требовать
 * адрес доставки от того, кто им не пользуется, незачем.
 */
object CustomerFormValidator {

    fun validate(form: CustomerForm): List<CustomerFormError> {
        val trimmed = form.trimmed()
        return buildList {
            when {
                trimmed.fullName.isEmpty() -> add(CustomerFormError.NameRequired)
                trimmed.fullName.length > CustomerForm.MAX_NAME_LENGTH ->
                    add(CustomerFormError.NameTooLong(CustomerForm.MAX_NAME_LENGTH))
            }
            if (trimmed.city == null) add(CustomerFormError.CityRequired)
            if (trimmed.address.length > CustomerForm.MAX_ADDRESS_LENGTH) {
                add(CustomerFormError.AddressTooLong(CustomerForm.MAX_ADDRESS_LENGTH))
            }
        }
    }
}
