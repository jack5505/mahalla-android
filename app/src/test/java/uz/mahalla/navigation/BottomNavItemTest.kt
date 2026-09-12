package uz.mahalla.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uz.mahalla.R

/**
 * Регресс на issue #211: подпись таба «Активности» — общая строка `nav_orders`
 * (старое «Заказы») больше не должна ни к чему резолвиться.
 */
@RunWith(RobolectricTestRunner::class)
class BottomNavItemTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `every tab label resolves to a non-blank string`() {
        BottomNavItem.entries.forEach { item ->
            val label = context.getString(item.labelRes)
            assertTrue("${item.name}.labelRes resolved to a blank string", label.isNotBlank())
        }
    }

    @Test
    fun `activity tab label is nav_activity`() {
        assertEquals(R.string.nav_activity, BottomNavItem.Orders.labelRes)
    }
}
