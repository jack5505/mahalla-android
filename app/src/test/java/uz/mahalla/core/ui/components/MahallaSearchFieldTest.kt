package uz.mahalla.core.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R
import uz.mahalla.ui.theme.MahallaTheme

/**
 * `MahallaSearchField` (issue #419): крестик очистки по умолчанию не меняет
 * поведение вызывающих без своего `onClear` ([PharmacyScreen], [FreelancersScreen]),
 * а экран с явным `onClear` получает именно его, а не запись пустой строки в
 * `onQueryChange`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class MahallaSearchFieldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clearLabel: String get() = compose.activity.getString(R.string.search_clear)

    @Test
    fun `tapping clear without onClear empties the query`() {
        var query = "osh"
        compose.setContent {
            MahallaTheme {
                MahallaSearchField(query = query, onQueryChange = { query = it })
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(clearLabel).performClick()

        assertEquals("", query)
    }

    @Test
    fun `tapping clear with onClear runs it instead of onQueryChange`() {
        var queryChangeCalls = 0
        var clearCalls = 0
        compose.setContent {
            MahallaTheme {
                MahallaSearchField(
                    query = "osh",
                    onQueryChange = { queryChangeCalls++ },
                    onClear = { clearCalls++ },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(clearLabel).performClick()

        assertEquals(1, clearCalls)
        assertEquals(0, queryChangeCalls)
    }
}
