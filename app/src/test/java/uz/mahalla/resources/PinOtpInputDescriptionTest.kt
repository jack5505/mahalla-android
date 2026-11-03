package uz.mahalla.resources

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R

/**
 * PIN и OTP из 4 и 6 цифр — реальные длины кодов в приложении, поэтому форма
 * plural для них проверяется явно, а не только парностью ключей (issue #418).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "ru")
class PinOtpInputDescriptionTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `pin description uses few form for four digits`() {
        val text = resources.getQuantityString(R.plurals.pin_input_description, 4, 4)

        assertEquals("PIN-код, 4 цифры", text)
    }

    @Test
    fun `pin description uses many form for six digits`() {
        val text = resources.getQuantityString(R.plurals.pin_input_description, 6, 6)

        assertEquals("PIN-код, 6 цифр", text)
    }

    @Test
    fun `otp description uses few form for four digits`() {
        val text = resources.getQuantityString(R.plurals.otp_input_description, 4, 4)

        assertEquals("Код подтверждения, 4 цифры", text)
    }

    @Test
    fun `otp description uses many form for six digits`() {
        val text = resources.getQuantityString(R.plurals.otp_input_description, 6, 6)

        assertEquals("Код подтверждения, 6 цифр", text)
    }
}
