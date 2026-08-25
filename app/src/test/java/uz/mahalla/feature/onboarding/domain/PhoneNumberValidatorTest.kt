package uz.mahalla.feature.onboarding.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberValidatorTest {

    private val validator = PhoneNumberValidator()

    @Test
    fun `keeps only digits of the national part`() {
        assertEquals("901234567", validator.nationalDigits("+998 90 123 45 67"))
        assertEquals("901234567", validator.nationalDigits("998901234567"))
        assertEquals("901234567", validator.nationalDigits("901234567"))
    }

    @Test
    fun `visible country code prefix is not treated as a number`() {
        // Поле ввода всегда начинается с +998: стартовое значение должно
        // давать пустую национальную часть, а не номер 99 8...
        assertEquals("", validator.nationalDigits("+998"))
        assertEquals("", validator.nationalDigits("+998 "))
        assertEquals("9", validator.nationalDigits("+998 9"))
        assertEquals("+998", validator.format(validator.nationalDigits("+998")))
    }

    @Test
    fun `does not eat operator code 99 of a short number`() {
        // Ровно девять цифр — код страны отрезать нельзя, иначе номер
        // оператора 99 превратился бы в шестизначный огрызок.
        assertEquals("998123456", validator.nationalDigits("998123456"))
    }

    @Test
    fun `never exceeds nine digits`() {
        assertEquals("901234567", validator.nationalDigits("9012345678901"))
        assertEquals(
            PhoneNumberValidator.NATIONAL_LENGTH,
            validator.nationalDigits("111111111111").length,
        )
    }

    @Test
    fun `validity requires full length and a known operator code`() {
        assertTrue(validator.isValid("901234567"))
        assertTrue(validator.isValid("331234567"))
        assertFalse("неполный номер", validator.isValid("90123456"))
        assertFalse("несуществующий код оператора", validator.isValid("101234567"))
    }

    @Test
    fun `completeness does not depend on the operator code`() {
        assertTrue(validator.isComplete("101234567"))
        assertFalse(validator.isComplete("10123456"))
    }

    @Test
    fun `formats progressively while typing`() {
        assertEquals("+998", validator.format(""))
        assertEquals("+998 9", validator.format("9"))
        assertEquals("+998 90", validator.format("90"))
        assertEquals("+998 90 1", validator.format("901"))
        assertEquals("+998 90 123", validator.format("90123"))
        assertEquals("+998 90 123 4", validator.format("901234"))
        assertEquals("+998 90 123 45", validator.format("9012345"))
        assertEquals("+998 90 123 45 67", validator.format("901234567"))
    }

    @Test
    fun `builds an e164 number`() {
        assertEquals("+998901234567", validator.toE164("901234567"))
    }
}
