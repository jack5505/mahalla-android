package uz.mahalla.feature.role.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.PlaceCategory

/**
 * «Мои заведения» (issue #94): правила, по которым экран решает, что можно
 * делать с заведением.
 *
 * Оба правила про одно и то же — не показывать действие, которое кончится
 * ошибкой: у заявки на модерации нет карточки в каталоге, а рядовой сотрудник
 * не переключает доступность.
 */
class MyPlaceTest {

    @Test
    fun `only a place the moderation let through has a card in the catalog`() {
        assertTrue(place(status = PlaceModerationStatus.Active).isOpenable)

        // `GET places/{id}` ответил бы «заведение не найдено».
        assertFalse(place(status = PlaceModerationStatus.Pending).isOpenable)
        assertFalse(place(status = PlaceModerationStatus.Suspended).isOpenable)
        assertFalse(place(status = PlaceModerationStatus.Closed).isOpenable)
        assertFalse(place(status = PlaceModerationStatus.Unknown).isOpenable)
    }

    @Test
    fun `availability is switched only for an active place`() {
        assertTrue(place(status = PlaceModerationStatus.Active).canToggleAvailability)

        // Закрывать на обед то, чего в каталоге ещё нет, незачем.
        assertFalse(place(status = PlaceModerationStatus.Pending).canToggleAvailability)
        assertFalse(place(status = PlaceModerationStatus.Suspended).canToggleAvailability)
    }

    @Test
    fun `a staff member does not get a switch that always fails`() {
        assertFalse(place(staffRole = PlaceStaffRole.Staff).canToggleAvailability)

        assertTrue(place(staffRole = PlaceStaffRole.Owner).canToggleAvailability)
        assertTrue(place(staffRole = PlaceStaffRole.Manager).canToggleAvailability)
    }

    @Test
    fun `silence about the role does not hide the switch from the owner`() {
        // Все поля `Mine` необязательны, и `role` может не приехать вовсе.
        assertTrue(place(staffRole = PlaceStaffRole.Unknown).canToggleAvailability)
    }

    @Test
    fun `roles are parsed case-insensitively and an unknown one is not an error`() {
        assertEquals(PlaceStaffRole.Owner, PlaceStaffRole.fromApi("OWNER"))
        assertEquals(PlaceStaffRole.Manager, PlaceStaffRole.fromApi(" manager "))
        assertEquals(PlaceStaffRole.Staff, PlaceStaffRole.fromApi("Staff"))
        assertEquals(PlaceStaffRole.Unknown, PlaceStaffRole.fromApi("CASHIER"))
        assertEquals(PlaceStaffRole.Unknown, PlaceStaffRole.fromApi(null))
        assertEquals(PlaceStaffRole.Unknown, PlaceStaffRole.fromApi(" "))
    }

    @Test
    fun `moderation statuses come from the schema enum`() {
        assertEquals(PlaceModerationStatus.Pending, PlaceModerationStatus.fromApi("PENDING"))
        assertEquals(PlaceModerationStatus.Active, PlaceModerationStatus.fromApi("active"))
        assertEquals(PlaceModerationStatus.Suspended, PlaceModerationStatus.fromApi("SUSPENDED"))
        assertEquals(PlaceModerationStatus.Closed, PlaceModerationStatus.fromApi("CLOSED"))
        // Новый статус бэкенда не должен прятать заведение из списка.
        assertEquals(PlaceModerationStatus.Unknown, PlaceModerationStatus.fromApi("ARCHIVED"))
    }

    private fun place(
        status: PlaceModerationStatus = PlaceModerationStatus.Active,
        staffRole: PlaceStaffRole = PlaceStaffRole.Owner,
    ) = MyPlace(
        id = "p-1",
        name = "Osh Markazi",
        category = PlaceCategory.Food,
        status = status,
        staffRole = staffRole,
    )
}
