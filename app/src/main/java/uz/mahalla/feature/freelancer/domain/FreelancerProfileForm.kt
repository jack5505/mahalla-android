package uz.mahalla.feature.freelancer.domain

/**
 * Анкета мастера — форма выставления себя как исполнителя (issue #71).
 *
 * Уходит в `POST /api/v1/freelancers/me` (схема `FreelancerCreateRequest`),
 * одна ручка и на создание, и на правку. Обратная сторона той же сделки, что и
 * форма заказа ([FreelancerOrderDraft]): там человек заказывает услугу, здесь —
 * выставляет её.
 *
 * Числа хранятся строками, а не `Int`: поле, которое человек чистит до пустого,
 * иначе пришлось бы показывать нулём, а «0 сум в час» — это не «не указано».
 * Разбирают их [hourlyRate] и [experienceYears], и они же ловят набор, который
 * в `int32` не влезает.
 *
 * Телефон — национальные цифры без `+998`, как на экране ввода номера:
 * форматированием занимается поле кита, а домен работает с цифрами.
 *
 * @param city свободный текст, а не [uz.mahalla.feature.onboarding.domain.City]:
 * бэкенд хранит здесь строку и показывает её в каталоге как есть (у мастеров
 * стенда — «Toshkent»), а id города приложения (`tashkent`) попал бы в чужую
 * карточку именно этим словом. Анкета продавца (issue #84) шлёт id, но там у
 * поля другой смысл — по нему ищут заведения.
 */
data class FreelancerProfileForm(
    val name: String = "",
    val profession: String = "",
    val city: String = "",
    val bio: String = "",
    val phoneDigits: String = "",
    val hourlyRateText: String = "",
    val experienceYearsText: String = "",
) {

    fun trimmed(): FreelancerProfileForm = copy(
        name = name.trim(),
        profession = profession.trim(),
        city = city.trim(),
        bio = bio.trim(),
    )

    /** Ставка за час или `null` — «не указана». Мусор тоже даёт `null`. */
    val hourlyRate: Int? get() = hourlyRateText.toIntOrNull()?.takeIf { it >= 0 }

    val experienceYears: Int? get() = experienceYearsText.toIntOrNull()?.takeIf { it >= 0 }

    companion object {
        /** Ограничения — из схемы `FreelancerCreateRequest`. */
        const val MAX_NAME_LENGTH = 200
        const val MAX_PROFESSION_LENGTH = 100
        const val MAX_CITY_LENGTH = 100
        const val MAX_BIO_LENGTH = 2000

        /** Короче двух букв ни имя, ни специальность не бывают. */
        const val MIN_NAME_LENGTH = 2

        /** Уже сохранённая анкета — та же форма: правят её тем же экраном. */
        fun of(freelancer: Freelancer, phoneDigits: String): FreelancerProfileForm =
            FreelancerProfileForm(
                name = freelancer.name,
                profession = freelancer.profession.orEmpty(),
                city = freelancer.city.orEmpty(),
                bio = freelancer.bio.orEmpty(),
                phoneDigits = phoneDigits,
                hourlyRateText = freelancer.hourlyRateSum.takeIf { it > 0 }?.toString().orEmpty(),
                experienceYearsText = freelancer.experienceYears?.toString().orEmpty(),
            )
    }
}

/** Что не так с анкетой мастера. Каждая ошибка привязана к своему полю. */
sealed interface FreelancerProfileFormError {
    data object NameRequired : FreelancerProfileFormError
    data class NameTooShort(val min: Int) : FreelancerProfileFormError
    data class NameTooLong(val max: Int) : FreelancerProfileFormError
    data object ProfessionRequired : FreelancerProfileFormError
    data class ProfessionTooLong(val max: Int) : FreelancerProfileFormError
    data class CityTooLong(val max: Int) : FreelancerProfileFormError
    data class BioTooLong(val max: Int) : FreelancerProfileFormError
    data object PhoneInvalid : FreelancerProfileFormError
    data object HourlyRateInvalid : FreelancerProfileFormError
    data object ExperienceInvalid : FreelancerProfileFormError
}

/**
 * Проверка анкеты мастера — до сети.
 *
 * Правила взяты из схемы, а не придуманы: обязательны имя и специальность,
 * длины полей — те же, что проверит бэкенд. Ошибки возвращаются все сразу:
 * форма длинная, и показывать замечания по одному значит гонять человека по
 * экрану (то же решение, что в анкете продавца, issue #84).
 *
 * Телефон **необязателен** — в схеме у него нет `@NotBlank`. Но заполненный
 * проверяется: по нему клиент звонит из карточки мастера, и «901234» там
 * никуда не дозвонится.
 */
object FreelancerProfileFormValidator {

    /**
     * @param isPhoneValid проверку номера делает `PhoneNumberValidator` — он
     * живёт в онбординге и знает коды операторов Узбекистана. Передаётся
     * параметром, чтобы домен мастера не тянул за собой инъекцию (то же
     * решение, что в анкете продавца).
     */
    fun validate(
        form: FreelancerProfileForm,
        isPhoneValid: (String) -> Boolean,
    ): List<FreelancerProfileFormError> {
        val trimmed = form.trimmed()
        return buildList {
            when {
                trimmed.name.isEmpty() -> add(FreelancerProfileFormError.NameRequired)
                trimmed.name.length < FreelancerProfileForm.MIN_NAME_LENGTH ->
                    add(
                        FreelancerProfileFormError.NameTooShort(
                            FreelancerProfileForm.MIN_NAME_LENGTH,
                        ),
                    )

                trimmed.name.length > FreelancerProfileForm.MAX_NAME_LENGTH ->
                    add(
                        FreelancerProfileFormError.NameTooLong(
                            FreelancerProfileForm.MAX_NAME_LENGTH,
                        ),
                    )
            }

            when {
                trimmed.profession.isEmpty() -> add(FreelancerProfileFormError.ProfessionRequired)
                trimmed.profession.length > FreelancerProfileForm.MAX_PROFESSION_LENGTH ->
                    add(
                        FreelancerProfileFormError.ProfessionTooLong(
                            FreelancerProfileForm.MAX_PROFESSION_LENGTH,
                        ),
                    )
            }

            if (trimmed.city.length > FreelancerProfileForm.MAX_CITY_LENGTH) {
                add(FreelancerProfileFormError.CityTooLong(FreelancerProfileForm.MAX_CITY_LENGTH))
            }
            if (trimmed.bio.length > FreelancerProfileForm.MAX_BIO_LENGTH) {
                add(FreelancerProfileFormError.BioTooLong(FreelancerProfileForm.MAX_BIO_LENGTH))
            }

            if (trimmed.phoneDigits.isNotEmpty() && !isPhoneValid(trimmed.phoneDigits)) {
                add(FreelancerProfileFormError.PhoneInvalid)
            }

            // Пустое поле — «не указано» и это разрешено; непустое, но
            // нечитаемое или не влезающее в `int32`, — ошибка, а не молчаливый
            // ноль: ставку человек называет клиентам.
            if (trimmed.hourlyRateText.isNotEmpty() && trimmed.hourlyRate == null) {
                add(FreelancerProfileFormError.HourlyRateInvalid)
            }
            if (trimmed.experienceYearsText.isNotEmpty() && trimmed.experienceYears == null) {
                add(FreelancerProfileFormError.ExperienceInvalid)
            }
        }
    }
}
