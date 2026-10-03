package uz.mahalla.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import uz.mahalla.R
import uz.mahalla.core.result.ApiError

/**
 * Маппинг 403 на текст (issue #344): гео получает свою подсказку, когда
 * сервер сам не прислал сообщение — остальные коды не сведены в общий бакет,
 * см. KDoc `ApiError.Forbidden` про то, почему.
 */
class ApiErrorMessagesTest {

    @Test
    fun `a codeless forbidden falls back to the generic no-rights text`() {
        assertEquals(R.string.error_forbidden, ApiError.Forbidden().messageRes())
    }

    @Test
    fun `a geo code gets its own hint, not the generic no-rights text`() {
        val res = ApiError.Forbidden(ApiError.Forbidden.GEO_PERMISSION_REQUIRED).messageRes()

        assertEquals(R.string.error_geo_required, res)
        assertNotEquals(R.string.error_forbidden, res)
        assertEquals(
            R.string.error_geo_required,
            ApiError.Forbidden(ApiError.Forbidden.GEO_INVALID_COORDINATES).messageRes(),
        )
    }

    @Test
    fun `an unrelated code still falls back to the generic no-rights text`() {
        // Контракт не даёт общего признака для «гейта» и подобных кодов
        // (docs/API-CONTRACT.md) — угадывать его здесь не стали.
        assertEquals(R.string.error_forbidden, ApiError.Forbidden("PLACE_FORBIDDEN").messageRes())
    }
}
