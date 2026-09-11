package uz.mahalla.feature.freelancer.domain

import androidx.compose.runtime.Immutable

/**
 * Кабинет мастера (issue #190): собственная услуга в списке, который мастер
 * редактирует сам — в отличие от [uz.mahalla.feature.booking.domain.BarberService]
 * это не то, что видит и заказывает клиент, а то, чем мастер управляет.
 *
 * @param priceSum цена в сумах — тийины уже пересчитаны в мапперe
 * (issue #149). Ноль — цена не названа.
 */
@Immutable
data class FreelancerCabinetService(
    val id: String,
    val title: String = "",
    val description: String? = null,
    val priceSum: Long = 0,
    val durationMinutes: Int = 0,
    val isActive: Boolean = true,
)

/**
 * Анкета мастера (issue #190) — форма `POST freelancers/me`, общая для первой
 * отправки и последующей правки: своего `PUT` у бэкенда нет.
 *
 * Обязательны только [name] и [profession] — та же пара, что показывается в
 * каталоге ([Freelancer.name], [Freelancer.profession]); остальные поля в
 * ответе `ProfileResponse` необязательны, и здесь по той же причине.
 */
@Immutable
data class FreelancerAnketaForm(
    val name: String = "",
    val profession: String = "",
    val bio: String = "",
    val city: String = "",
    val phoneDigits: String = "",
    val hourlyRateText: String = "",
    val experienceYearsText: String = "",
) {
    fun trimmed(): FreelancerAnketaForm = copy(
        name = name.trim(),
        profession = profession.trim(),
        bio = bio.trim(),
        city = city.trim(),
    )

    /** Ставка в сумах из того, что набрано в поле; мусор — `null` (не отправится). */
    val hourlyRateSum: Long? get() = hourlyRateText.filter(Char::isDigit).toLongOrNull()

    val experienceYears: Int? get() = experienceYearsText.filter(Char::isDigit).toIntOrNull()

    companion object {
        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 200
        const val MAX_PROFESSION_LENGTH = 100
        const val MAX_BIO_LENGTH = 2000
    }
}

sealed interface FreelancerAnketaFormError {
    data object NameRequired : FreelancerAnketaFormError
    data class NameTooShort(val min: Int) : FreelancerAnketaFormError
    data class NameTooLong(val max: Int) : FreelancerAnketaFormError
    data object ProfessionRequired : FreelancerAnketaFormError
    data class ProfessionTooLong(val max: Int) : FreelancerAnketaFormError
    data class BioTooLong(val max: Int) : FreelancerAnketaFormError
    data object PhoneInvalid : FreelancerAnketaFormError
}

/**
 * Проверка анкеты до сети — по той же причине, что [ProviderFormValidator]:
 * заведомо неполную анкету отправлять незачем, бэкенд ответит тем же самым,
 * но ценой запроса и спиннера.
 *
 * Телефон необязателен (в отличие от анкеты продавца): мастер и так вошёл по
 * своему номеру, звонить ему в приложении не через это поле — но если номер
 * всё же вписан, кривой формат отправлять незачем.
 */
object FreelancerAnketaFormValidator {

    fun validate(
        form: FreelancerAnketaForm,
        isPhoneValid: (String) -> Boolean,
    ): List<FreelancerAnketaFormError> {
        val trimmed = form.trimmed()
        return buildList {
            when {
                trimmed.name.isEmpty() -> add(FreelancerAnketaFormError.NameRequired)
                trimmed.name.length < FreelancerAnketaForm.MIN_NAME_LENGTH ->
                    add(FreelancerAnketaFormError.NameTooShort(FreelancerAnketaForm.MIN_NAME_LENGTH))

                trimmed.name.length > FreelancerAnketaForm.MAX_NAME_LENGTH ->
                    add(FreelancerAnketaFormError.NameTooLong(FreelancerAnketaForm.MAX_NAME_LENGTH))
            }

            when {
                trimmed.profession.isEmpty() -> add(FreelancerAnketaFormError.ProfessionRequired)
                trimmed.profession.length > FreelancerAnketaForm.MAX_PROFESSION_LENGTH ->
                    add(
                        FreelancerAnketaFormError.ProfessionTooLong(
                            FreelancerAnketaForm.MAX_PROFESSION_LENGTH,
                        ),
                    )
            }

            if (trimmed.bio.length > FreelancerAnketaForm.MAX_BIO_LENGTH) {
                add(FreelancerAnketaFormError.BioTooLong(FreelancerAnketaForm.MAX_BIO_LENGTH))
            }

            if (trimmed.phoneDigits.isNotEmpty() && !isPhoneValid(trimmed.phoneDigits)) {
                add(FreelancerAnketaFormError.PhoneInvalid)
            }
        }
    }
}

/**
 * Черновик услуги мастера — добавление и правка используют одну форму
 * (issue #190): бэкенду обе идут одним и тем же `ServiceRequest`.
 */
@Immutable
data class FreelancerServiceDraft(
    val title: String = "",
    val description: String = "",
    val priceText: String = "",
    val durationText: String = "",
    val isActive: Boolean = true,
) {
    val trimmedTitle: String get() = title.trim()
    val trimmedDescription: String get() = description.trim()

    val priceSum: Long? get() = priceText.filter(Char::isDigit).toLongOrNull()?.takeIf { it > 0 }

    val durationMinutes: Int? get() = durationText.filter(Char::isDigit).toIntOrNull()?.takeIf { it > 0 }

    companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 1000

        fun from(service: FreelancerCabinetService): FreelancerServiceDraft = FreelancerServiceDraft(
            title = service.title,
            description = service.description.orEmpty(),
            priceText = service.priceSum.takeIf { it > 0 }?.toString().orEmpty(),
            durationText = service.durationMinutes.takeIf { it > 0 }?.toString().orEmpty(),
            isActive = service.isActive,
        )
    }
}

sealed interface FreelancerServiceFormError {
    data object TitleRequired : FreelancerServiceFormError
    data class TitleTooLong(val max: Int) : FreelancerServiceFormError
    data class DescriptionTooLong(val max: Int) : FreelancerServiceFormError
    data object PriceRequired : FreelancerServiceFormError
    data object DurationRequired : FreelancerServiceFormError
}

object FreelancerServiceFormValidator {

    fun validate(draft: FreelancerServiceDraft): List<FreelancerServiceFormError> = buildList {
        when {
            draft.trimmedTitle.isEmpty() -> add(FreelancerServiceFormError.TitleRequired)
            draft.trimmedTitle.length > FreelancerServiceDraft.MAX_TITLE_LENGTH ->
                add(FreelancerServiceFormError.TitleTooLong(FreelancerServiceDraft.MAX_TITLE_LENGTH))
        }
        if (draft.trimmedDescription.length > FreelancerServiceDraft.MAX_DESCRIPTION_LENGTH) {
            add(FreelancerServiceFormError.DescriptionTooLong(FreelancerServiceDraft.MAX_DESCRIPTION_LENGTH))
        }
        if (draft.priceSum == null) add(FreelancerServiceFormError.PriceRequired)
        if (draft.durationMinutes == null) add(FreelancerServiceFormError.DurationRequired)
    }
}
