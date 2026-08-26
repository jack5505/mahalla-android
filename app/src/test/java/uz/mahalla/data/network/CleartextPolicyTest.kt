package uz.mahalla.data.network

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Разрешение на незашифрованный трафик (issue #26).
 *
 * Само правило задаётся `network_security_config.xml` и зависит от сборки —
 * здесь закреплено то, что от неё не зависит: https проходит всегда, а мусор
 * адресом не считается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CleartextPolicyTest {

    private val policy = AndroidCleartextPolicy()

    @Test
    fun `https is allowed regardless of the config`() {
        assertTrue(policy.isAllowed("https://api.mahalla.uz/"))
    }

    @Test
    fun `garbage is not an address`() {
        assertFalse(policy.isAllowed("не адрес"))
        assertFalse(policy.isAllowed(""))
    }
}
