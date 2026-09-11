package uz.mahalla.core.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.ui.theme.MahallaTheme

/**
 * Хвост пагинируемого списка (issue #214) — раньше `LoadMoreItem` был
 * скопирован в одиннадцать экранов (плюс `FashionUi.FashionLoadMore`), и
 * поведение проверялось (если проверялось вообще) заново на каждом. Теперь
 * это один компонент в два варианта, и тест — тоже один, здесь.
 *
 * [LoadMoreButton] уже был прикрыт `ActivityScreenTest` (issue #151) —
 * тот тест остаётся как регресс на курсор `ActivityState.nextPages`, здесь же
 * проверяется сам компонент, независимо от экрана. [LoadMoreAuto] раньше не
 * был прикрыт композиционным тестом вовсе: риск авто-триггера (`itemCount`
 * не меняется — эффект не должен просить страницу второй раз) проверялся
 * только вёрсткой на глаз.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class LoadMoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var loadMoreCalls = 0

    private val loadMoreLabel: String get() = compose.activity.getString(R.string.action_load_more)
    private val retryLabel: String get() = compose.activity.getString(R.string.action_retry)

    @Test
    fun `button - tap requests one page`() {
        setButtonContent(isLoading = false, failure = null)

        compose.onNodeWithText(loadMoreLabel).performClick()

        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun `button - busy while a page is in flight`() {
        setButtonContent(isLoading = true, failure = null)

        compose.onNodeWithText(loadMoreLabel).assertIsNotEnabled()
        // Занятая и недоступная — разное состояние для TalkBack.
        compose.onNodeWithText(loadMoreLabel).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                compose.activity.getString(R.string.state_loading),
            ),
        )
        compose.onNodeWithText(loadMoreLabel).performClick()

        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `button - a failure hides the button behind a reason and a retry`() {
        setButtonContent(isLoading = false, failure = ApiFailure(ApiError.NoConnection))

        compose.onNodeWithText(loadMoreLabel).assertDoesNotExist()
        compose.onNodeWithText(retryLabel).performClick()

        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun `auto - requests a page once it is composed`() {
        setAutoContent(mutableStateOf(5))

        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun `auto - does not ask again while the item count stays the same`() {
        val itemCount = mutableStateOf(5)
        setAutoContent(itemCount)
        assertEquals(1, loadMoreCalls)

        // Пересборка без реального изменения ключа — ровно то, из-за чего
        // ломался старый триггер на курсоре (issue #151): здесь ключ —
        // количество строк, и оно не сдвинулось.
        compose.runOnIdle { itemCount.value = itemCount.value }
        compose.waitForIdle()

        assertEquals(1, loadMoreCalls)
    }

    @Test
    fun `auto - asks again once the list actually grew`() {
        val itemCount = mutableStateOf(5)
        setAutoContent(itemCount)
        assertEquals(1, loadMoreCalls)

        compose.runOnIdle { itemCount.value = 10 }
        compose.waitForIdle()

        assertEquals(2, loadMoreCalls)
    }

    @Test
    fun `auto - a failure shows a retry instead of retriggering automatically`() {
        setAutoContent(mutableStateOf(5), failure = ApiFailure(ApiError.NoConnection))

        // Провал — не тот случай, когда список вырос: автотриггер не должен
        // был сработать вовсе.
        assertEquals(0, loadMoreCalls)
        compose.onNodeWithText(retryLabel).performClick()

        assertEquals(1, loadMoreCalls)
    }

    private fun setButtonContent(isLoading: Boolean, failure: ApiFailure?) {
        compose.setContent {
            MahallaTheme {
                LoadMoreButton(
                    isLoading = isLoading,
                    failure = failure,
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun setAutoContent(itemCount: MutableState<Int>, failure: ApiFailure? = null) {
        compose.setContent {
            MahallaTheme {
                LoadMoreAuto(
                    itemCount = itemCount.value,
                    isLoading = false,
                    failure = failure,
                    onLoadMore = { loadMoreCalls++ },
                )
            }
        }
        compose.waitForIdle()
    }
}
