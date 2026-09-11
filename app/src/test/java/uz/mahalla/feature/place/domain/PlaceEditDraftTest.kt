package uz.mahalla.feature.place.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик правки карточки места (issue #188).
 *
 * Ограничения — из схемы бэкенда `UpdateRequest`: ошибка здесь стоит либо
 * отказа сервера на разрешённую клиентом кнопку, либо заблокированной кнопки
 * при заполненной форме.
 */
class PlaceEditDraftTest {

    @Test
    fun `an empty name cannot be sent`() {
        assertFalse(PlaceEditDraft().canSubmit)
        assertFalse(PlaceEditDraft().isNameValid)
    }

    @Test
    fun `a name alone is enough`() {
        // Остальные поля у бэкенда необязательны — заведение можно назвать,
        // не трогая ничего больше.
        val draft = PlaceEditDraft(name = "Osh markazi")

        assertTrue(draft.canSubmit)
    }

    @Test
    fun `blank name is the same as no name`() {
        assertFalse(PlaceEditDraft(name = "   ").canSubmit)
    }

    @Test
    fun `the limit of the backend is the limit of the form`() {
        val exact = PlaceEditDraft(name = "a".repeat(PlaceEditDraft.MAX_NAME_LENGTH))
        assertTrue(exact.isNameValid)

        val tooLong = PlaceEditDraft(name = "a".repeat(PlaceEditDraft.MAX_NAME_LENGTH + 1))
        assertFalse("Отправлять заведомо отвергнутое тело незачем", tooLong.isNameValid)
        assertFalse(tooLong.canSubmit)
    }

    @Test
    fun `each field has its own ceiling from the schema`() {
        val draft = PlaceEditDraft(
            name = "Osh markazi",
            address = "a".repeat(PlaceEditDraft.MAX_ADDRESS_LENGTH + 1),
        )

        assertFalse(draft.isAddressValid)
        assertFalse(draft.canSubmit)
        // Поле, которое не перебрало предел, само по себе валидно.
        assertTrue(draft.isNameValid)
    }

    @Test
    fun `an empty website is not an invalid one`() {
        // Сайт необязателен — пустое поле не должно блокировать отправку.
        val draft = PlaceEditDraft(name = "Osh markazi", website = "")

        assertTrue(draft.isWebsiteValid)
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `a website without a scheme is accepted with https added`() {
        val draft = PlaceEditDraft(name = "Osh markazi", website = "oshmarkazi.uz")

        assertTrue(draft.isWebsiteValid)
    }

    @Test
    fun `a website with a foreign scheme is rejected`() {
        val draft = PlaceEditDraft(name = "Osh markazi", website = "market://oshmarkazi")

        assertFalse(draft.isWebsiteValid)
        assertFalse(draft.canSubmit)
    }

    @Test
    fun `fields are trimmed before validation`() {
        val draft = PlaceEditDraft(name = "  Osh markazi  ")

        assertTrue(draft.isNameValid)
        assertTrue(draft.canSubmit)
    }
}
