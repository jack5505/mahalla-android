package uz.mahalla.feature.business.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole

/**
 * Ролевой доступ (эпик #16): разделение витрины клиента и панели бизнеса.
 *
 * Проверяется не «есть ли доступ вообще» — это решает `places/my`, — а то, что
 * набор разделов зависит и от категории заведения, и от роли человека: панель
 * парикмахерской, показавшая «входящие заказы», позвала бы ручку еды за чужую
 * вертикаль.
 */
class BusinessAccessTest {

    @Test
    fun `a barbershop has a queue, no food sections, and earnings`() {
        val access = access(category = PlaceCategory.Master)

        assertEquals(
            listOf(BusinessSection.Queue, BusinessSection.Earnings),
            access.sections,
        )
    }

    @Test
    fun `a food place has orders and a menu, but no queue`() {
        val access = access(category = PlaceCategory.Food)

        assertEquals(
            listOf(BusinessSection.Orders, BusinessSection.Menu, BusinessSection.Earnings),
            access.sections,
        )
    }

    /**
     * У бэкенда нет ручек ни для очереди, ни для заказов этой категории — но
     * `wallet/business` общий для всех, и заработок виден в любом случае
     * (issue #290).
     */
    @Test
    fun `a pharmacy has no category-specific sections, only earnings`() {
        val access = access(category = PlaceCategory.Pharmacy)

        assertEquals(listOf(BusinessSection.Earnings), access.sections)
    }

    @Test
    fun `a staff member sees the orders but cannot edit the menu or earnings`() {
        val access = access(category = PlaceCategory.Food, role = PlaceStaffRole.Staff)

        assertEquals(listOf(BusinessSection.Orders), access.sections)
        assertFalse(access.canManageMenu)
        assertFalse(access.canOpen(BusinessSection.Menu))
        assertFalse(access.canOpen(BusinessSection.Earnings))
    }

    @Test
    fun `a manager edits the menu just like the owner`() {
        val access = access(category = PlaceCategory.Food, role = PlaceStaffRole.Manager)

        assertTrue(access.canManageMenu)
        assertTrue(access.canOpen(BusinessSection.Menu))
    }

    /**
     * Молчание сервера о роли — не повод запереть владельца в его же
     * заведении: все поля `Mine` необязательны (issue #94).
     */
    @Test
    fun `an unknown role is treated as an owner, not as a staff member`() {
        val access = access(category = PlaceCategory.Food, role = PlaceStaffRole.Unknown)

        assertTrue(access.canManageMenu)
        assertTrue(access.canPause)
    }

    @Test
    fun `a staff member cannot pause the place`() {
        val access = access(category = PlaceCategory.Food, role = PlaceStaffRole.Staff)

        assertFalse(access.canPause)
    }

    /**
     * У заявки `PENDING` карточки в каталоге нет вовсе, значит ни заказов, ни
     * очереди быть не может — и открывать разделы незачем.
     */
    @Test
    fun `sections of a place under moderation do not open, except earnings`() {
        val access = access(
            category = PlaceCategory.Food,
            status = PlaceModerationStatus.Pending,
        )

        assertFalse(access.isOperational)
        assertFalse(access.canOpen(BusinessSection.Orders))
        assertFalse(access.canPause)
        // Сам список разделов при этом остаётся — экран показывает их
        // недоступными вместе с объяснением, а не прячет.
        assertEquals(
            listOf(BusinessSection.Orders, BusinessSection.Menu, BusinessSection.Earnings),
            access.sections,
        )
        // Заработок — исключение: бизнес-кошелёк общий для всех заведений
        // владельца, а не для этого конкретного, и уже заработанные деньги не
        // должны запираться модерацией именно этой карточки (issue #290).
        assertTrue(access.canOpen(BusinessSection.Earnings))
    }

    @Test
    fun `a suspended place is not operational either`() {
        val access = access(status = PlaceModerationStatus.Suspended)

        assertFalse(access.isOperational)
        assertFalse(access.canOpen(BusinessSection.Orders))
    }

    @Test
    fun `the access is built from the place of the my-places list`() {
        val place = MyPlace(
            id = "p-9",
            name = "Barber Studio",
            category = PlaceCategory.Master,
            status = PlaceModerationStatus.Active,
            isAvailable = true,
            staffRole = PlaceStaffRole.Manager,
        )

        val access = BusinessAccess.from(place)

        assertEquals("p-9", access.placeId)
        assertEquals("Barber Studio", access.placeName)
        assertEquals(PlaceStaffRole.Manager, access.role)
        assertTrue(access.isAvailable)
        assertEquals(
            listOf(BusinessSection.Queue, BusinessSection.Earnings),
            access.sections,
        )
    }

    private fun access(
        category: PlaceCategory = PlaceCategory.Food,
        role: PlaceStaffRole = PlaceStaffRole.Owner,
        status: PlaceModerationStatus = PlaceModerationStatus.Active,
    ) = BusinessAccess(
        placeId = "p-1",
        placeName = "Osh Markazi",
        category = category,
        role = role,
        status = status,
        isAvailable = true,
    )
}
