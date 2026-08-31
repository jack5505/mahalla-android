package uz.mahalla.feature.services.domain

import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator

/**
 * Анкета исполнителя — вторая половина issue #71: чем человек готов помочь и
 * почём (`GET`/`POST freelancers/me`, схема `ProfileResponse`).
 *
 * [isAvailable] — переключатель «принимаю заказы»: он же управляется
 * `PUT freelancers/me/toggle-availability` и в каталоге `GET freelancers`
 * решает, показывать исполнителя или нет.
 *
 * Рейтинг и число оценок считает сервер — на форме они только показываются.
 */
data class ServiceOffer(
    val id: String? = null,
    val name: String = "",
    val profession: String = "",
    val bio: String = "",
    val city: String = "",
    val phone: String = "",
    val hourlyRateSum: Long? = null,
    val experienceYears: Int? = null,
    val isAvailable: Boolean = true,
    val ratingAverage: Double? = null,
    val ratingCount: Int = 0,
)

/**
 * Форма выставления услуги.
 *
 * Цена и стаж хранятся строками, а не числами: поле ввода умеет быть пустым и
 * недописанным, а `Long`/`Int` — нет. Разбор и границы — в
 * [ServiceOfferValidator], туда же смотрит и кнопка «сохранить».
 *
 * [phoneDigits] — девять национальных цифр без `+998` (как в
 * `MahallaPhoneField` и во всём онбординге).
 */
data class ServiceOfferForm(
    val name: String = "",
    val profession: String = "",
    val bio: String = "",
    val city: String = "",
    val phoneDigits: String = "",
    val hourlyRate: String = "",
    val experienceYears: String = "",
) {
    companion object {
        /** Форма из анкеты, которую вернул сервер: экран открывается на своих данных. */
        fun of(offer: ServiceOffer, phone: PhoneNumberValidator): ServiceOfferForm =
            ServiceOfferForm(
                name = offer.name,
                profession = offer.profession,
                bio = offer.bio,
                city = offer.city,
                phoneDigits = phone.nationalDigits(offer.phone),
                hourlyRate = offer.hourlyRateSum?.takeIf { it > 0 }?.toString().orEmpty(),
                experienceYears = offer.experienceYears?.takeIf { it > 0 }?.toString().orEmpty(),
            )
    }
}

/** Что не так с анкетой. Каждая ошибка привязана к своему полю. */
sealed interface ServiceOfferError {
    data object NameRequired : ServiceOfferError
    data class NameTooLong(val maxLength: Int) : ServiceOfferError

    data object ProfessionRequired : ServiceOfferError
    data class ProfessionTooLong(val maxLength: Int) : ServiceOfferError

    data object CityRequired : ServiceOfferError
    data class BioTooLong(val maxLength: Int) : ServiceOfferError

    /** Номер набран, но такого оператора не существует. */
    data object PhoneInvalid : ServiceOfferError

    /** Цена — не число, ноль или больше потолка. */
    data class RateInvalid(val maxSum: Long) : ServiceOfferError

    data class ExperienceInvalid(val maxYears: Int) : ServiceOfferError
}

/**
 * Валидация анкеты — чистая функция от формы.
 *
 * Обязательны имя, профессия и город: по ним исполнителя ищут в каталоге
 * (`GET freelancers?profession&city`), и без них анкета не находится вовсе.
 * Цена, стаж и телефон необязательны — «договоримся» это нормальный ответ, а
 * номер у аккаунта уже есть.
 *
 * Потолок цены не эстетический: `hourlyRate` у бэкенда `int32`, и сумма
 * больше двух миллиардов уедет в переполнение на его стороне.
 */
object ServiceOfferValidator {

    const val MAX_NAME_LENGTH = 100
    const val MAX_PROFESSION_LENGTH = 80
    const val MAX_BIO_LENGTH = 1000
    const val MAX_RATE_SUM = 100_000_000L
    const val MAX_EXPERIENCE_YEARS = 70

    fun validate(form: ServiceOfferForm, phone: PhoneNumberValidator): List<ServiceOfferError> =
        buildList {
            val name = form.name.trim()
            when {
                name.isEmpty() -> add(ServiceOfferError.NameRequired)
                name.length > MAX_NAME_LENGTH -> add(ServiceOfferError.NameTooLong(MAX_NAME_LENGTH))
            }

            val profession = form.profession.trim()
            when {
                profession.isEmpty() -> add(ServiceOfferError.ProfessionRequired)
                profession.length > MAX_PROFESSION_LENGTH ->
                    add(ServiceOfferError.ProfessionTooLong(MAX_PROFESSION_LENGTH))
            }

            if (form.city.trim().isEmpty()) add(ServiceOfferError.CityRequired)

            if (form.bio.trim().length > MAX_BIO_LENGTH) {
                add(ServiceOfferError.BioTooLong(MAX_BIO_LENGTH))
            }

            val digits = form.phoneDigits.trim()
            if (digits.isNotEmpty() && !phone.isValid(digits)) add(ServiceOfferError.PhoneInvalid)

            if (form.hourlyRate.isNotBlank() && rateSum(form) == null) {
                add(ServiceOfferError.RateInvalid(MAX_RATE_SUM))
            }

            if (form.experienceYears.isNotBlank() && experienceYears(form) == null) {
                add(ServiceOfferError.ExperienceInvalid(MAX_EXPERIENCE_YEARS))
            }
        }

    fun canSubmit(form: ServiceOfferForm, phone: PhoneNumberValidator): Boolean =
        validate(form, phone).isEmpty()

    /** Цена в сумах или `null`, если поле пустое либо заполнено неверно. */
    fun rateSum(form: ServiceOfferForm): Long? {
        val raw = form.hourlyRate.trim()
        if (raw.isEmpty()) return null
        val value = raw.toLongOrNull() ?: return null
        return value.takeIf { it > 0 && it <= MAX_RATE_SUM }
    }

    fun experienceYears(form: ServiceOfferForm): Int? {
        val raw = form.experienceYears.trim()
        if (raw.isEmpty()) return null
        val value = raw.toIntOrNull() ?: return null
        return value.takeIf { it >= 0 && it <= MAX_EXPERIENCE_YEARS }
    }
}
