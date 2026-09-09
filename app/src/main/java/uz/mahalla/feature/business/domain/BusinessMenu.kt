package uz.mahalla.feature.business.domain

/**
 * Позиция меню **глазами заведения** (задача 12.4).
 *
 * Схема та же (`ItemResponse`), что видит клиент, но смысл [isAvailable]
 * обратный: у клиента это «можно заказать», у владельца — переключатель
 * стоп-листа.
 */
data class BusinessMenuItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val priceSum: Long = 0,
    val prepMinutes: Int? = null,
    val isAvailable: Boolean = true,
    val isHalal: Boolean = false,
) {
    /** В стоп-листе — то, что сейчас не отдают. */
    val isStopped: Boolean get() = !isAvailable
}

/**
 * Раздел меню. У бэкенда «меню» заведения — это и есть категория: у него своё
 * имя и свои позиции (`MenuResponse`), и их у заведения несколько.
 *
 * [id] здесь — тот самый `menuId`, который требует `CreateItemRequest`: новая
 * позиция создаётся не «в заведении», а в конкретном разделе.
 */
data class BusinessMenuSection(
    val id: String,
    val name: String,
    val items: List<BusinessMenuItem> = emptyList(),
)

/** Меню целиком. */
data class BusinessMenu(
    val sections: List<BusinessMenuSection> = emptyList(),
) {
    val stoppedCount: Int get() = sections.sumOf { section -> section.items.count { it.isStopped } }

    fun item(itemId: String): BusinessMenuItem? =
        sections.firstNotNullOfOrNull { section -> section.items.firstOrNull { it.id == itemId } }
}

/**
 * Форма новой позиции (задача 12.4).
 *
 * Цена хранится строкой, как её набрал человек: `Long` в поле ввода означал бы
 * молча съеденные символы, а «12о00» лучше показать ошибкой, чем превратить в
 * 1200 (то же правило, что в анкете продавца, issue #84).
 *
 * Полей ровно столько, сколько принимает `CreateItemRequest` — `menuId`,
 * `name`, `description`, `price`, `prepMinutes`, `isHalal`. Картинки среди них
 * нет: у `ItemResponse` ссылки на фото не существует в схеме вовсе (issue
 * #60), и поле загрузки обещало бы кухне то, чего сервер не примет.
 */
data class NewMenuItemForm(
    val sectionId: String = "",
    val name: String = "",
    val priceText: String = "",
    val description: String = "",
    val prepMinutesText: String = "",
    val isHalal: Boolean = false,
) {

    fun trimmed(): NewMenuItemForm = copy(
        name = name.trim(),
        priceText = priceText.trim(),
        description = description.trim(),
        prepMinutesText = prepMinutesText.trim(),
    )

    /** `null` — не число или не влезает в `Long`; проверку делает валидатор. */
    fun priceOrNull(): Long? = priceText.trim().takeIf(String::isNotEmpty)?.toLongOrNull()

    fun prepMinutesOrNull(): Int? = prepMinutesText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()

    companion object {
        /** `@Size(max = 200)` у `CreateItemRequest.name`. */
        const val MAX_NAME_LENGTH = 200

        /** `@Size(max = 1000)` у `CreateItemRequest.description`. */
        const val MAX_DESCRIPTION_LENGTH = 1000

        /**
         * `@Min(1000)` у `CreateItemRequest.price`. То есть цены дешевле
         * тысячи сумов бэкенд не примет — и сказать об этом до отправки
         * честнее, чем показать его 400.
         */
        const val MIN_PRICE_SUM = 1_000L

        /**
         * Ограничение наше: у бэкенда верхней границы цены нет. Миллиард сумов
         * за блюдо — это опечатка в разрядах, а не позиция меню.
         */
        const val MAX_PRICE_SUM = 1_000_000_000L

        /** Тоже наше: сутки на приготовление — уже не «время ожидания». */
        const val MAX_PREP_MINUTES = 24 * 60
    }
}

/** Что не так с формой. Каждая ошибка привязана к своему полю. */
sealed interface NewMenuItemError {
    data object SectionRequired : NewMenuItemError
    data object NameRequired : NewMenuItemError
    data class NameTooLong(val max: Int) : NewMenuItemError
    data class DescriptionTooLong(val max: Int) : NewMenuItemError
    data object PriceRequired : NewMenuItemError
    data object PriceNotANumber : NewMenuItemError
    data class PriceTooSmall(val min: Long) : NewMenuItemError
    data class PriceTooLarge(val max: Long) : NewMenuItemError
    data object PrepMinutesNotANumber : NewMenuItemError
    data class PrepMinutesTooLarge(val max: Int) : NewMenuItemError
}

/**
 * Проверка формы до отправки.
 *
 * Ошибки возвращаются **все сразу**: форма короткая, но показывать замечания
 * по одному — это заставить нажимать «сохранить» четыре раза (то же решение,
 * что у `WalkInRequestValidator`).
 */
object NewMenuItemValidator {

    fun validate(form: NewMenuItemForm): List<NewMenuItemError> {
        val trimmed = form.trimmed()
        return buildList {
            if (trimmed.sectionId.isBlank()) add(NewMenuItemError.SectionRequired)

            when {
                trimmed.name.isEmpty() -> add(NewMenuItemError.NameRequired)
                trimmed.name.length > NewMenuItemForm.MAX_NAME_LENGTH ->
                    add(NewMenuItemError.NameTooLong(NewMenuItemForm.MAX_NAME_LENGTH))
            }

            if (trimmed.description.length > NewMenuItemForm.MAX_DESCRIPTION_LENGTH) {
                add(
                    NewMenuItemError.DescriptionTooLong(
                        NewMenuItemForm.MAX_DESCRIPTION_LENGTH,
                    ),
                )
            }

            val price = trimmed.priceOrNull()
            when {
                trimmed.priceText.isEmpty() -> add(NewMenuItemError.PriceRequired)
                price == null -> add(NewMenuItemError.PriceNotANumber)
                price < NewMenuItemForm.MIN_PRICE_SUM ->
                    add(NewMenuItemError.PriceTooSmall(NewMenuItemForm.MIN_PRICE_SUM))

                price > NewMenuItemForm.MAX_PRICE_SUM ->
                    add(NewMenuItemError.PriceTooLarge(NewMenuItemForm.MAX_PRICE_SUM))
            }

            // Время приготовления необязательно: пустое поле — не ошибка,
            // а «заведение не обещает срок».
            if (trimmed.prepMinutesText.isNotEmpty()) {
                val minutes = trimmed.prepMinutesOrNull()
                when {
                    minutes == null || minutes < 0 -> add(NewMenuItemError.PrepMinutesNotANumber)
                    minutes > NewMenuItemForm.MAX_PREP_MINUTES ->
                        add(NewMenuItemError.PrepMinutesTooLarge(NewMenuItemForm.MAX_PREP_MINUTES))
                }
            }
        }
    }
}
