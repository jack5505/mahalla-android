package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ServerError

/** Домен входа через Telegram-бот (issue #46). */
class TelegramLoginTest {

    // --- Ссылка на бота ---

    @Test
    fun `official telegram hosts pass`() {
        listOf(
            "https://t.me/MahallaVerifyBot?start=abc",
            "https://telegram.me/MahallaVerifyBot?start=abc",
            "https://telegram.dog/MahallaVerifyBot?start=abc",
            "tg://resolve?domain=MahallaVerifyBot&start=abc",
        ).forEach { url ->
            assertEquals("ссылка $url ведёт в Telegram", url, TelegramBotLink.sanitize(url))
        }
    }

    @Test
    fun `host is matched case-insensitively and with a port`() {
        assertNotNull(TelegramBotLink.sanitize("https://T.ME/Bot?start=abc"))
        assertNotNull(TelegramBotLink.sanitize("https://t.me:443/Bot?start=abc"))
    }

    /**
     * Главное правило безопасности экрана: ссылку присылает сервер, а адрес
     * сервера в debug вводит пользователь. Открыть по ней можно только
     * Telegram — иначе подменённый бэкенд запускал бы на устройстве
     * произвольный intent.
     */
    @Test
    fun `anything that is not telegram is rejected`() {
        listOf(
            "https://evil.example/MahallaBot?start=abc",
            "http://t.me/Bot?start=abc",
            "market://details?id=com.example",
            "intent://evil#Intent;scheme=https;end",
            "mahalla://place/42",
            "javascript:alert(1)",
            "/relative/path",
            "",
            "   ",
        ).forEach { url ->
            assertNull("ссылка $url открываться не должна", TelegramBotLink.sanitize(url))
        }
        assertNull(TelegramBotLink.sanitize(null))
    }

    /**
     * `https://t.me@evil.example/` — это хост `evil.example`: всё до `@`
     * является userinfo. Наивная проверка «начинается с t.me» пропустила бы
     * такую ссылку.
     */
    @Test
    fun `userinfo does not disguise a foreign host`() {
        assertNull(TelegramBotLink.sanitize("https://t.me@evil.example/Bot?start=abc"))
        assertNull(TelegramBotLink.sanitize("https://t.me.evil.example/Bot?start=abc"))
    }

    // --- Сборка испытания ---

    @Test
    fun `challenge needs both a token and a telegram link`() {
        assertNull(
            "без токена проверять нечего",
            TelegramChallenge.of(null, "https://t.me/Bot?start=a", 300),
        )
        assertNull(TelegramChallenge.of("  ", "https://t.me/Bot?start=a", 300))
        assertNull(
            "чужую ссылку мы всё равно не откроем",
            TelegramChallenge.of("token", "https://evil.example/", 300),
        )
    }

    @Test
    fun `expiry falls back to the default when the server sends nonsense`() {
        val url = "https://t.me/Bot?start=a"
        assertEquals(
            TelegramChallenge.DEFAULT_EXPIRES_SECONDS,
            TelegramChallenge.of("token", url, null)?.expiresInSeconds,
        )
        assertEquals(
            "отрицательный срок закрыл бы экран сразу после открытия",
            TelegramChallenge.DEFAULT_EXPIRES_SECONDS,
            TelegramChallenge.of("token", url, -5)?.expiresInSeconds,
        )
        assertEquals(
            TelegramChallenge.DEFAULT_EXPIRES_SECONDS,
            TelegramChallenge.of("token", url, 99_999)?.expiresInSeconds,
        )
        assertEquals(120, TelegramChallenge.of("token", url, 120)?.expiresInSeconds)
    }

    // --- Классификация ответов ---

    @Test
    fun `TG_PENDING is a wait, not a failure`() {
        assertTrue(failure(ApiError.Http(400, null), code = "TG_PENDING").isTelegramPending())
    }

    @Test
    fun `other server codes are real failures`() {
        assertFalse(failure(ApiError.Http(400, null), code = "VALIDATION_ERROR").isTelegramPending())
        assertFalse(failure(ApiError.Http(400, null), code = "TG_EXPIRED").isTelegramPending())
        // Без тела «ожиданием» считать нечего: опрос крутился бы вслепую.
        assertFalse(ApiFailure(ApiError.NoConnection).isTelegramPending())
    }

    @Test
    fun `polling survives a lost connection but not a refusal`() {
        assertTrue(ApiFailure(ApiError.NoConnection).isTelegramPollRecoverable())
        assertTrue(ApiFailure(ApiError.Timeout).isTelegramPollRecoverable())
        assertTrue(ApiFailure(ApiError.Http(503, null)).isTelegramPollRecoverable())

        assertFalse(ApiFailure(ApiError.Http(400, null)).isTelegramPollRecoverable())
        assertFalse(ApiFailure(ApiError.Forbidden).isTelegramPollRecoverable())
        assertFalse(ApiFailure(ApiError.Business("TG_EXPIRED")).isTelegramPollRecoverable())
    }

    // --- Расписание опроса ---

    @Test
    fun `poll interval grows and stops at the cap`() {
        val delays = (0..10).map { TelegramPollSchedule.delayMillisAt(it) }

        assertEquals(TelegramPollSchedule.FIRST_DELAY_MILLIS, delays.first())
        assertEquals(
            "пауза не убывает",
            delays.sorted(),
            delays,
        )
        assertEquals(
            "и не растёт бесконечно",
            TelegramPollSchedule.MAX_DELAY_MILLIS,
            delays.last(),
        )
        val allowed = TelegramPollSchedule.FIRST_DELAY_MILLIS..TelegramPollSchedule.MAX_DELAY_MILLIS
        assertTrue(delays.all { it in allowed })
    }

    private fun failure(error: ApiError, code: String) = ApiFailure(
        error = error,
        server = ServerError(httpCode = 400, code = code, message = "kutilmoqda"),
    )
}
