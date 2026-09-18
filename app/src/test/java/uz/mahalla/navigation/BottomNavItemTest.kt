package uz.mahalla.navigation

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uz.mahalla.R

/**
 * Подписи табов нижней навигации (issue #211).
 *
 * Таб вёл на `activity/ActivityScreen` — один список из заказов, броней
 * игровых зон, записей к мастеру и врачу и билетов в кино, — а назывался
 * `nav_orders` («Заказы»/«Buyurtmalar»), то есть обещал меньше, чем экран
 * показывает. Ловится здесь именно пользовательский текст: переименование
 * ключа ресурса не компилируется, а вот возврат старой формулировки в
 * `strings.xml` проходил молча — обе локали оставались согласованными, и
 * `StringResourceParityTest` был зелёным.
 *
 * Ширину подписи юнит-тестом не проверить: Robolectric не считает метрики
 * шрифта (`Paint.measureText` возвращает длину строки в символах). За тем, что
 * подпись влезает в бокс таба при системном fontScale 1.3, следит кегль 9/600
 * из ТЗ — см. `MahallaBottomNav`.
 */
@RunWith(RobolectricTestRunner::class)
class BottomNavItemTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** uz — основной язык (`values/`), ru — `values-ru/`. */
    private fun contextFor(language: String): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }

    @Test
    fun `activity tab is labelled with the activity wording, not orders`() {
        assertEquals(R.string.nav_activity, BottomNavItem.Orders.labelRes)
        assertEquals("Amallarim", contextFor("uz").getString(R.string.nav_activity))
        assertEquals("Активности", contextFor("ru").getString(R.string.nav_activity))
    }

    @Test
    fun `every tab label resolves in both locales`() {
        listOf("uz", "ru").forEach { language ->
            val localized = contextFor(language)
            BottomNavItem.entries.forEach { item ->
                val label = localized.getString(item.labelRes)
                assertTrue("${item.name}.labelRes пуст в $language", label.isNotBlank())
            }
        }
    }
}
