package uz.mahalla.feature.place.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черновик отзыва (issue #76).
 *
 * Правила формы проверяются здесь, а не глазами: ошибка в них кончается либо
 * отказом сервера на кнопку, которую он сам же и разрешил нажать, либо
 * заблокированной кнопкой при заполненной форме.
 */
class ReviewDraftTest {

    @Test
    fun `an empty draft cannot be sent`() {
        assertFalse(ReviewDraft().canSubmit)
        assertFalse(ReviewDraft().isRated)
    }

    @Test
    fun `a rating alone is a review`() {
        // Текст необязателен: бэкенд требует только оценку, и заставлять
        // человека писать слова, чтобы поставить пять звёзд, незачем.
        val draft = ReviewDraft().withRating(5)

        assertTrue(draft.canSubmit)
        assertNull(draft.textOrNull())
    }

    @Test
    fun `only the range the backend accepts counts as a rating`() {
        for (rating in ReviewDraft.RATING_RANGE) {
            assertTrue("$rating", ReviewDraft(rating = rating).isRated)
        }
        assertFalse(ReviewDraft(rating = 0).isRated)
        assertFalse(ReviewDraft(rating = 6).isRated)
        assertFalse(ReviewDraft(rating = -1).isRated)
    }

    @Test
    fun `whitespace is neither length nor content`() {
        val draft = ReviewDraft(rating = 4, text = "   \n  ")

        assertEquals("", draft.trimmedText)
        assertNull(draft.textOrNull())
        assertTrue(draft.canSubmit)
    }

    @Test
    fun `text is trimmed before it goes to the server`() {
        assertEquals("Zo'r", ReviewDraft(rating = 4, text = "  Zo'r  ").textOrNull())
    }

    @Test
    fun `the limit of the backend is the limit of the form`() {
        val exact = ReviewDraft(rating = 5, text = "a".repeat(ReviewDraft.MAX_TEXT_LENGTH))
        assertFalse(exact.isTooLong)
        assertTrue(exact.canSubmit)

        val tooLong = ReviewDraft(rating = 5, text = "a".repeat(ReviewDraft.MAX_TEXT_LENGTH + 1))
        assertTrue(tooLong.isTooLong)
        assertFalse("Отправлять заведомо отвергнутое тело незачем", tooLong.canSubmit)
    }

    @Test
    fun `trailing spaces do not push the text over the limit`() {
        // Иначе кнопка выключалась бы из-за пробела, которого человек не видит.
        val draft = ReviewDraft(
            rating = 5,
            text = "a".repeat(ReviewDraft.MAX_TEXT_LENGTH) + "   ",
        )

        assertFalse(draft.isTooLong)
    }
}
