package uz.mahalla.data.network.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отпечаток сертификата (issue #32).
 *
 * Формат важен ровно потому, что строку сверяет глазами человек: она должна
 * совпадать с выводом `openssl x509 -noout -fingerprint -sha256`.
 */
class CertificateFingerprintTest {

    @Test
    fun `fingerprint is 32 bytes in openssl form`() {
        val fingerprint = CertificateFingerprint.of(HeldCertificate.Builder().build().certificate)

        val bytes = fingerprint.split(':')
        assertEquals(32, bytes.size)
        assertTrue("байты в верхнем регистре по два знака", bytes.all { it.matches(HEX) })
    }

    @Test
    fun `different certificates have different fingerprints`() {
        val first = CertificateFingerprint.of(HeldCertificate.Builder().build().certificate)
        val second = CertificateFingerprint.of(HeldCertificate.Builder().build().certificate)

        assertFalse(CertificateFingerprint.matches(first, second))
    }

    @Test
    fun `the same certificate matches itself`() {
        val certificate = HeldCertificate.Builder().build().certificate

        assertTrue(
            CertificateFingerprint.matches(
                CertificateFingerprint.of(certificate),
                CertificateFingerprint.of(certificate),
            ),
        )
    }

    @Test
    fun `record differences do not matter`() {
        // В хранилище мог лечь отпечаток, скопированный из другого источника:
        // сравнивать нужно значение, а не запись.
        assertTrue(CertificateFingerprint.matches("ab:cd:ef", "AB:CD:EF"))
        assertTrue(CertificateFingerprint.matches("ABCDEF", "AB:CD:EF"))
        assertTrue(CertificateFingerprint.matches("AB CD EF", "AB:CD:EF"))
    }

    @Test
    fun `a missing pin matches nothing`() {
        // Иначе «доверия не выдавали» означало бы «доверяем любому сертификату».
        assertFalse(CertificateFingerprint.matches(null, "AB:CD:EF"))
        assertFalse(CertificateFingerprint.matches("", "AB:CD:EF"))
        assertFalse(CertificateFingerprint.matches("   ", "AB:CD:EF"))
        assertFalse(CertificateFingerprint.matches("AB:CD:EF", null))
    }

    private companion object {
        val HEX = Regex("[0-9A-F]{2}")
    }
}
