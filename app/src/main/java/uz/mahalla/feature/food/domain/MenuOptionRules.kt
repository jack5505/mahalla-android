package uz.mahalla.feature.food.domain

/**
 * Правила выбора модификаторов (эпик 5.1) — чистые функции, чтобы шторка не
 * решала это сама и всё проверялось юнит-тестом.
 *
 * Выбор хранится плоским множеством id: группы у одной позиции не пересекаются
 * по идентификаторам вариантов, а разбирать вложенную структуру в состоянии
 * экрана незачем.
 */
object MenuOptionRules {

    /**
     * Нажатие на вариант. В одиночной группе выбор заменяется (радио), в
     * множественной — добавляется, пока не упёрлись в [OptionGroup.maxChoices];
     * упёрлись — нажатие игнорируется, а не выкидывает чужой вариант молча.
     *
     * Повторное нажатие снимает выбор только там, где группа это допускает:
     * в обязательной одиночной группе снять последний вариант нельзя — иначе
     * позиция окажется в состоянии, которое сервер не примет.
     */
    fun toggle(group: OptionGroup, selected: Set<String>, optionId: String): Set<String> {
        val option = group.option(optionId) ?: return selected
        if (!option.isAvailable) return selected

        val groupIds = group.options.mapTo(mutableSetOf(), MenuOption::id)
        val chosenHere = selected intersect groupIds

        if (optionId in chosenHere) {
            val remaining = chosenHere.size - 1
            return if (remaining < group.minChoices) selected else selected - optionId
        }

        return when {
            group.isSingleChoice -> selected - chosenHere + optionId
            chosenHere.size < group.maxChoices -> selected + optionId
            else -> selected
        }
    }

    /**
     * Предвыбор: обязательная одиночная группа открывается с первым доступным
     * вариантом. Пустая обязательная группа — это заблокированная кнопка на
     * ровном месте.
     */
    fun defaultSelection(item: MenuItem): Set<String> =
        item.optionGroups
            .filter { it.isRequired && it.isSingleChoice }
            .mapNotNullTo(mutableSetOf()) { group -> group.availableOptions.firstOrNull()?.id }

    /**
     * Что мешает положить позицию в корзину. Пустой список — можно.
     * Возвращаются все проблемы сразу: показывать их по одной значит заставить
     * человека тыкать кнопку столько раз, сколько групп он пропустил.
     */
    fun validate(item: MenuItem, selected: Set<String>): List<SelectionError> = buildList {
        // Невыполнимая обязательная группа — это недоступная позиция, а не
        // «заполните группу»: заполнять её нечем.
        if (!item.isOrderable) add(SelectionError.Unavailable)
        item.optionGroups.forEach { group ->
            val chosen = chosenIn(group, selected)
            if (chosen.size < group.minChoices) {
                add(SelectionError.RequiredGroup(group.id, group.name))
            }
            if (chosen.any { group.option(it)?.isAvailable == false }) {
                add(SelectionError.OptionUnavailable(group.id))
            }
        }
    }

    fun isComplete(item: MenuItem, selected: Set<String>): Boolean =
        validate(item, selected).isEmpty()

    /** Цена позиции с модификаторами: база плюс дельты выбранных вариантов. */
    fun price(item: MenuItem, selected: Set<String>): Long =
        item.priceSum + item.optionGroups.sumOf { group ->
            chosenIn(group, selected).sumOf { id -> group.option(id)?.priceDeltaSum ?: 0L }
        }

    /**
     * Выбранные варианты в порядке групп и вариантов меню, а не в порядке
     * нажатий: подпись строки корзины не должна зависеть от того, что человек
     * ткнул первым.
     */
    fun chosenOptions(item: MenuItem, selected: Set<String>): List<MenuOption> =
        item.optionGroups.flatMap { group ->
            group.options.filter { it.id in selected }
        }

    private fun chosenIn(group: OptionGroup, selected: Set<String>): List<String> =
        group.options.map(MenuOption::id).filter { it in selected }
}

/** Почему позицию нельзя добавить. Тексты — в ресурсах, домен их не знает. */
sealed interface SelectionError {
    /** Позиция в стоп-листе. */
    data object Unavailable : SelectionError

    /** Обязательная группа не заполнена. */
    data class RequiredGroup(val groupId: String, val groupName: String) : SelectionError

    /** Выбранный вариант успел уехать в стоп-лист. */
    data class OptionUnavailable(val groupId: String) : SelectionError
}
