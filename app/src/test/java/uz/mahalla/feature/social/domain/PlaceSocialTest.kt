package uz.mahalla.feature.social.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила лайка и черновика комментария (issue #75) — чистые функции, ради
 * которых экран и остаётся тестируемым: оптимистичное нажатие обязано
 * считаться одинаково и при нажатии, и при откате.
 */
class PlaceSocialTest {

    @Test
    fun `a tap flips the like and moves the counter by one`() {
        val status = PlaceSocialStatus(liked = false, likes = 10)

        val liked = status.toggledLike()

        assertTrue(liked.liked)
        assertEquals(11L, liked.likes)
        assertEquals(status, liked.toggledLike())
    }

    @Test
    fun `removing a like never sends the counter below zero`() {
        // Счётчик мог приехать нулевым из-за гонки с другим устройством, а
        // «−1 лайк» на экране объяснить нечем.
        val status = PlaceSocialStatus(liked = true, likes = 0)

        assertEquals(0L, status.toggledLike().likes)
    }

    @Test
    fun `my like means the counter is at least one`() {
        val status = PlaceSocialStatus.of(liked = true, saved = false, likes = 0)

        assertEquals(1L, status.likes)
    }

    @Test
    fun `the server counter wins over the optimistic one`() {
        // Между нажатием и ответом место могли лайкнуть ещё десять человек.
        val optimistic = PlaceSocialStatus(liked = true, likes = 11)

        val confirmed = optimistic.withLike(liked = true, likes = 21)

        assertEquals(21L, confirmed.likes)
    }

    @Test
    fun `confirming a like already applied does not count it twice`() {
        // Ответ без счётчика — обычное дело: `LikeResponse.totalLikes`
        // необязателен, и подставлять ноль вместо него нельзя.
        val optimistic = PlaceSocialStatus(liked = true, likes = 11)

        assertEquals(11L, optimistic.withLike(liked = true, likes = null).likes)
    }

    @Test
    fun `a server answer that disagrees with the tap wins`() {
        // Сервер уже знал о лайке с другого устройства: клиент подстраивается
        // под него, а не остаётся в собственном, третьем состоянии.
        val optimistic = PlaceSocialStatus(liked = true, likes = 11)

        val answer = optimistic.withLike(liked = false, likes = null)

        assertFalse(answer.liked)
        assertEquals(10L, answer.likes)
    }

    @Test
    fun `saving is a separate flag and does not touch likes`() {
        val status = PlaceSocialStatus(liked = true, saved = false, likes = 5)

        val saved = status.toggledSave()

        assertTrue(saved.saved)
        assertTrue(saved.liked)
        assertEquals(5L, saved.likes)
        assertFalse(saved.withSaved(false).saved)
    }

    @Test
    fun `a blank comment cannot be sent`() {
        assertFalse(CommentRules.canSubmit(""))
        assertFalse(CommentRules.canSubmit("   \n "))
        assertTrue(CommentRules.canSubmit(" Zo'r joy "))
    }

    @Test
    fun `whitespace is trimmed before sending`() {
        assertEquals("Zo'r joy", CommentRules.normalize("  Zo'r joy \n"))
    }

    @Test
    fun `a comment longer than the limit is rejected`() {
        val long = "a".repeat(CommentRules.MAX_LENGTH + 1)

        assertFalse(CommentRules.canSubmit(long))
        assertTrue(CommentRules.canSubmit("a".repeat(CommentRules.MAX_LENGTH)))
    }
}
