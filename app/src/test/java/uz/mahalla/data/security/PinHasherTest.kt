package uz.mahalla.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    private val salt = ByteArray(PinHasher.SALT_LENGTH_BYTES) { it.toByte() }

    @Test
    fun `hash is deterministic for the same pin and salt`() {
        assertArrayContentEquals(PinHasher.hash("1234", salt), PinHasher.hash("1234", salt))
    }

    @Test
    fun `different pins give different hashes`() {
        assertNotEquals(
            PinHasher.hash("1234", salt).toList(),
            PinHasher.hash("4321", salt).toList(),
        )
    }

    @Test
    fun `same pin with another salt gives another hash`() {
        val otherSalt = ByteArray(PinHasher.SALT_LENGTH_BYTES) { (it + 1).toByte() }

        assertNotEquals(
            PinHasher.hash("1234", salt).toList(),
            PinHasher.hash("1234", otherSalt).toList(),
        )
    }

    @Test
    fun `verify accepts the right pin and rejects the wrong one`() {
        val hash = PinHasher.hash("1234", salt)

        assertTrue(PinHasher.verify("1234", salt, hash))
        assertFalse(PinHasher.verify("1235", salt, hash))
        assertFalse(PinHasher.verify("", salt, hash))
    }

    @Test
    fun `salt is random and of the documented length`() {
        val first = PinHasher.newSalt()
        val second = PinHasher.newSalt()

        assertEquals(PinHasher.SALT_LENGTH_BYTES, first.size)
        assertNotEquals(first.toList(), second.toList())
    }

    private fun assertArrayContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
