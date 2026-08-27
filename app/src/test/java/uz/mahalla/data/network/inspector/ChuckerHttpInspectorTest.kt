package uz.mahalla.data.network.inspector

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Инспектор трафика на Chucker (issue #30).
 *
 * Юнит-тесты идут по debug-варианту, поэтому здесь закрепляется поведение
 * сборки с настоящей библиотекой: интерцептор есть, экран открывается. Ветка
 * no-op (release) проверяется сборкой `assembleRelease` — подменить
 * `Chucker.isOp` в тесте нечем, это константа артефакта.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChuckerHttpInspectorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `debug build ships a working inspector`() {
        val inspector = ChuckerHttpInspector(context)

        assertTrue("в debug приезжает настоящий Chucker", inspector.isAvailable)
        assertNotNull("интерцептор попадёт в цепочку OkHttp", inspector.interceptor)
    }

    @Test
    fun `interceptor is created once`() {
        // Клиентов два (основной и refresh) — база транзакций у них общая,
        // иначе запросы разъехались бы по двум спискам.
        val inspector = ChuckerHttpInspector(context)

        assertTrue(inspector.interceptor === inspector.interceptor)
    }

    @Test
    fun `screen with the requests can be opened`() {
        val inspector = ChuckerHttpInspector(context)

        assertNotNull(inspector.launchIntent())
    }
}
