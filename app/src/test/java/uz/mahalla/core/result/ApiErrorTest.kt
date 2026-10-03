package uz.mahalla.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация машинного кода 403 (issue #344): гео — единственный код,
 * который контракт документирует и для которого есть отдельная подсказка.
 * Прочие коды (`PLACE_FORBIDDEN`, `WALLET_BLOCKED`, вероятный гейт вертикали)
 * не сведены в одну категорию — контракт не даёт общего признака, по которому
 * их можно было бы отличить друг от друга, см. `docs/API-CONTRACT.md`.
 */
class ApiErrorTest {

    @Test
    fun `a codeless forbidden is not geo`() {
        assertFalse(ApiError.Forbidden().isGeo)
    }

    @Test
    fun `both geo codes are recognized as geo`() {
        assertTrue(ApiError.Forbidden(ApiError.Forbidden.GEO_PERMISSION_REQUIRED).isGeo)
        assertTrue(ApiError.Forbidden(ApiError.Forbidden.GEO_INVALID_COORDINATES).isGeo)
    }

    @Test
    fun `an unrelated code is not geo`() {
        assertFalse(ApiError.Forbidden("PLACE_FORBIDDEN").isGeo)
    }

    @Test
    fun `equality holds by code, as the cache eviction rule relies on it`() {
        assertEquals(ApiError.Forbidden(), ApiError.Forbidden(null))
        assertEquals(
            ApiError.Forbidden("GEO_PERMISSION_REQUIRED"),
            ApiError.Forbidden("GEO_PERMISSION_REQUIRED"),
        )
        assertNotEquals(ApiError.Forbidden(), ApiError.Forbidden("GEO_PERMISSION_REQUIRED"))
    }
}
