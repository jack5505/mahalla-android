package uz.mahalla.feature.freelancer.domain

import uz.mahalla.feature.booking.domain.BarberService

/**
 * Форма выставления услуги (issue #71): что мастер предлагает и за сколько.
 *
 * Уходит в `POST freelancers/me/services` (новая) или
 * `PUT freelancers/me/services/{serviceId}` (правка) — схема `ServiceRequest`,
 * тело у обеих ручек одно и то же. Именно эту услугу потом выбирает клиент в
 * форме заказа ([FreelancerOrderDraft]).
 *
 * Числа хранятся строками по той же причине, что и в анкете
 * ([FreelancerProfileForm]): пустое поле — это «не указано», а не ноль.
 *
 * @param id правится существующая услуга; `null` — выставляется новая. От
 * этого зависит только метод запроса, поля формы одни и те же.
 */
data class FreelancerServiceForm(
    val id: String? = null,
    val title: String = "",
    val description: String = "",
    val priceText: String = "",
    val durationText: String = "",
) {

    fun trimmed(): FreelancerServiceForm = copy(
        title = title.trim(),
        description = description.trim(),
    )

    /** Цена в сумах или `null`, если набрано нечитаемое. `int64` у бэкенда. */
    val price: Long? get() = priceText.toLongOrNull()?.takeIf { it >= 0 }

    /** Длительность в минутах или `null` — «не указана». */
    val duration: Int? get() = durationText.toIntOrNull()?.takeIf { it >= 0 }

    val isNew: Boolean get() = id == null

    companion object {
        /** Ограничения — из схемы `ServiceRequest`. */
        const val MAX_TITLE_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2000

        /** Уже выставленная услуга — та же форма: правят её тем же полем. */
        fun of(service: BarberService): FreelancerServiceForm = FreelancerServiceForm(
            id = service.id,
            title = service.title,
            description = service.description.orEmpty(),
            priceText = service.priceSum.takeIf { it > 0 }?.toString().orEmpty(),
            durationText = service.durationMinutes?.toString().orEmpty(),
        )
    }
}

/** Что не так с услугой. Каждая ошибка привязана к своему полю. */
sealed interface FreelancerServiceFormError {
    data object TitleRequired : FreelancerServiceFormError
    data class TitleTooLong(val max: Int) : FreelancerServiceFormError
    data class DescriptionTooLong(val max: Int) : FreelancerServiceFormError
    data object PriceRequired : FreelancerServiceFormError
    data object PriceInvalid : FreelancerServiceFormError
    data object DurationInvalid : FreelancerServiceFormError
}

/**
 * Проверка услуги — до сети.
 *
 * Обязательны название и цена: так сказано в схеме (`required: [title,
 * priceAmount]`), и услуга без цены всё равно показалась бы клиенту пустой
 * строкой в списке заказа.
 *
 * Ноль ценой считается: `@Min(0)` бэкенда его разрешает, а «бесплатный осмотр»
 * — это осмысленное предложение. Клиенту такая услуга покажется без цены.
 */
object FreelancerServiceFormValidator {

    fun validate(form: FreelancerServiceForm): List<FreelancerServiceFormError> {
        val trimmed = form.trimmed()
        return buildList {
            when {
                trimmed.title.isEmpty() -> add(FreelancerServiceFormError.TitleRequired)
                trimmed.title.length > FreelancerServiceForm.MAX_TITLE_LENGTH ->
                    add(
                        FreelancerServiceFormError.TitleTooLong(
                            FreelancerServiceForm.MAX_TITLE_LENGTH,
                        ),
                    )
            }

            if (trimmed.description.length > FreelancerServiceForm.MAX_DESCRIPTION_LENGTH) {
                add(
                    FreelancerServiceFormError.DescriptionTooLong(
                        FreelancerServiceForm.MAX_DESCRIPTION_LENGTH,
                    ),
                )
            }

            when {
                trimmed.priceText.isEmpty() -> add(FreelancerServiceFormError.PriceRequired)
                trimmed.price == null -> add(FreelancerServiceFormError.PriceInvalid)
            }

            if (trimmed.durationText.isNotEmpty() && trimmed.duration == null) {
                add(FreelancerServiceFormError.DurationInvalid)
            }
        }
    }
}
