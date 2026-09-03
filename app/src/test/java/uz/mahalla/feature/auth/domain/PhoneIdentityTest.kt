package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure

/**
 * Сверка номера, под которым входят, с номером аккаунта из ответа (issue #86).
 *
 * Это единственный признак, по которому клиент может понять, что `pin-login`
 * вернул прежнего владельца устройства, а не того, кто сейчас вводит номер.
 */
class PhoneIdentityTest {

    @Test
    fun `same number in different notations is the same account`() {
        assertTrue(PhoneIdentity.isSame("+998901234567", "998901234567"))
        assertTrue(PhoneIdentity.isSame("+998 90 123 45 67", "901234567"))
        assertTrue(PhoneIdentity.isSame("998901234567", "+998-90-123-45-67"))
    }

    @Test
    fun `different numbers are different accounts`() {
        assertTrue(PhoneIdentity.isForeignAccount("+998901234567", "+998937555505"))
        assertFalse(PhoneIdentity.isForeignAccount("+998901234567", "+998901234567"))
    }

    @Test
    fun `numbers differing only in the national part are different`() {
        // Хвост, а не начало: код страны у обоих один, различаются абоненты.
        assertTrue(PhoneIdentity.isForeignAccount("+998901234567", "+998901234568"))
    }

    @Test
    fun `unknown number on either side is not a foreign account`() {
        // Ответ без `phone` — это «эндпоинт про другое», а не «аккаунт чужой»:
        // отказывать во входе на каждое отсутствующее поле значило бы сломать
        // вход целиком.
        assertFalse(PhoneIdentity.isForeignAccount(null, "+998901234567"))
        assertFalse(PhoneIdentity.isForeignAccount("+998901234567", null))
        assertFalse(PhoneIdentity.isForeignAccount("+998901234567", "   "))
        assertFalse(PhoneIdentity.isForeignAccount("+998901234567", "не номер"))
    }

    @Test
    fun `unknown number is not the same account either`() {
        // Иначе сброс прежней личности в `requestCode` пропускал бы аккаунт,
        // о котором приложение ничего не знает.
        assertFalse(PhoneIdentity.isSame(null, "+998901234567"))
        assertFalse(PhoneIdentity.isSame("+998901234567", null))
        assertNull(PhoneIdentity.significantDigits(""))
        assertNull(PhoneIdentity.significantDigits("+"))
    }

    @Test
    fun `short numbers are compared as they are`() {
        assertEquals("12345", PhoneIdentity.significantDigits("12345"))
        assertTrue(PhoneIdentity.isSame("12345", "12345"))
        assertTrue(PhoneIdentity.isForeignAccount("12345", "54321"))
    }

    @Test
    fun `only the client-made failure counts as a foreign account`() {
        assertTrue(
            ApiFailure(ApiError.Business(PhoneIdentity.FOREIGN_ACCOUNT_CODE)).isForeignAccount(),
        )
        assertFalse(ApiFailure(ApiError.Business("OTP_EXPIRED")).isForeignAccount())
        assertFalse(ApiFailure(ApiError.Unauthorized).isForeignAccount())
    }
}
