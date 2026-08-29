package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.testutil.menuItem
import uz.mahalla.testutil.menuOption
import uz.mahalla.testutil.optionGroup

/**
 * Правила выбора модификаторов (эпик 5.1). Всё, что решает, попадёт ли позиция
 * в корзину и по какой цене, проверяется здесь — шторка эти правила не
 * повторяет.
 */
class MenuOptionRulesTest {

    private val size = optionGroup(
        id = "size",
        minChoices = 1,
        maxChoices = 1,
        options = listOf(
            menuOption("small"),
            menuOption("large", priceDeltaSum = 10_000),
        ),
    )

    private val extras = optionGroup(
        id = "extras",
        maxChoices = 2,
        options = listOf(
            menuOption("cheese", priceDeltaSum = 5_000),
            menuOption("egg", priceDeltaSum = 4_000),
            menuOption("sauce", priceDeltaSum = 1_000),
        ),
    )

    private val item = menuItem("osh", priceSum = 30_000, optionGroups = listOf(size, extras))

    @Test
    fun `single choice group replaces the previous option`() {
        val selected = MenuOptionRules.toggle(size, setOf("small"), "large")

        assertEquals(setOf("large"), selected)
    }

    @Test
    fun `multi choice group adds up to the limit and then ignores taps`() {
        val two = MenuOptionRules.toggle(
            extras,
            MenuOptionRules.toggle(extras, emptySet(), "cheese"),
            "egg",
        )

        // Третья добавка при maxChoices = 2 не должна молча вытеснить чужую.
        assertEquals(setOf("cheese", "egg"), MenuOptionRules.toggle(extras, two, "sauce"))
    }

    @Test
    fun `tapping a chosen option removes it when the group allows`() {
        val selected = MenuOptionRules.toggle(extras, setOf("cheese"), "cheese")

        assertEquals(emptySet<String>(), selected)
    }

    @Test
    fun `the last option of a required group cannot be unselected`() {
        // Иначе позиция окажется в состоянии, которое сервер не примет.
        assertEquals(setOf("small"), MenuOptionRules.toggle(size, setOf("small"), "small"))
    }

    @Test
    fun `an option from the stop list cannot be chosen`() {
        val group = optionGroup(
            id = "extras",
            maxChoices = 2,
            options = listOf(menuOption("cheese", isAvailable = false)),
        )

        assertEquals(emptySet<String>(), MenuOptionRules.toggle(group, emptySet(), "cheese"))
    }

    @Test
    fun `required single choice group opens with the first available option`() {
        val group = optionGroup(
            id = "size",
            minChoices = 1,
            maxChoices = 1,
            options = listOf(menuOption("small", isAvailable = false), menuOption("large")),
        )

        assertEquals(
            setOf("large"),
            MenuOptionRules.defaultSelection(menuItem("osh", optionGroups = listOf(group))),
        )
    }

    @Test
    fun `an empty required group blocks adding and names itself`() {
        val errors = MenuOptionRules.validate(item, selected = emptySet())

        assertEquals(
            listOf("size"),
            errors.filterIsInstance<SelectionError.RequiredGroup>().map { it.groupId },
        )
        assertFalse(MenuOptionRules.isComplete(item, emptySet()))
    }

    @Test
    fun `every unfilled group is reported at once`() {
        val twoRequired = menuItem(
            "set",
            optionGroups = listOf(size, extras.copy(minChoices = 1)),
        )

        assertEquals(2, MenuOptionRules.validate(twoRequired, emptySet()).size)
    }

    @Test
    fun `an item from the stop list cannot be added at all`() {
        val stopped = menuItem("osh", isAvailable = false)

        assertTrue(MenuOptionRules.validate(stopped, emptySet()).contains(SelectionError.Unavailable))
    }

    @Test
    fun `a required group entirely in the stop list makes the item unavailable`() {
        // Раньше шторка открывалась с кнопкой, которая не включалась никогда:
        // предвыбрать нечего, а validate вечно возвращал RequiredGroup.
        val stoppedSize = optionGroup(
            id = "size",
            minChoices = 1,
            options = listOf(
                menuOption("small", isAvailable = false),
                menuOption("large", isAvailable = false),
            ),
        )
        val unorderable = menuItem("osh", optionGroups = listOf(stoppedSize))

        assertFalse(unorderable.isOrderable)
        assertEquals(
            listOf(SelectionError.Unavailable),
            MenuOptionRules.validate(unorderable, MenuOptionRules.defaultSelection(unorderable))
                .filterIsInstance<SelectionError.Unavailable>(),
        )
    }

    @Test
    fun `an item whose required group still has options stays orderable`() {
        assertTrue(item.isOrderable)
        assertTrue(MenuOptionRules.isComplete(item, MenuOptionRules.defaultSelection(item)))
    }

    @Test
    fun `price is the base plus the deltas of the chosen options`() {
        val price = MenuOptionRules.price(item, setOf("large", "cheese"))

        assertEquals(30_000L + 10_000L + 5_000L, price)
    }

    @Test
    fun `a negative delta lowers the price`() {
        val group = optionGroup(
            id = "meat",
            options = listOf(menuOption("no-meat", priceDeltaSum = -5_000)),
        )
        val cheaper = menuItem("osh", priceSum = 30_000, optionGroups = listOf(group))

        assertEquals(25_000L, MenuOptionRules.price(cheaper, setOf("no-meat")))
    }

    @Test
    fun `chosen options follow the menu order, not the order of taps`() {
        // Подпись строки корзины не должна зависеть от того, что ткнули первым.
        val names = MenuOptionRules.chosenOptions(item, setOf("sauce", "cheese", "large"))
            .map(MenuOption::id)

        assertEquals(listOf("large", "cheese", "sauce"), names)
    }
}
