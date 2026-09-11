package uz.mahalla.feature.activity.ui

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
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.ui.theme.MahallaTheme
import java.time.Instant

/**
 * Хвост «моих активностей» (issue #151).
 *
 * Тест на композицию, а не на ViewModel, потому что и риск был в дереве:
 * догрузку запускал `LaunchedEffect(state.nextPages)` внутри хвоста списка.
 * Курсор сдвигается после каждой удачной страницы, значит эффект
 * перезапускался сам — и останавливала это только вёрстка: список должен был
 * перерасти экран и вытеснить хвост из видимой области. У человека с парой
 * активностей он не перерастает никогда, поэтому открытие таба вычитывало все
 * страницы всех пяти источников подряд. Ни один тест уровня ViewModel этого
 * видеть не мог: события `LoadMore` там приходят от теста, а не от экрана.
 *
 * Экран 393×852 dp — базовый по ТЗ, плотность зафиксирована (`mdpi`), чтобы
 * dp и px совпадали.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class ActivityScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<ActivityEvent>()

    private val loadMore: String get() = compose.activity.getString(R.string.activity_load_more)
    private val retry: String get() = compose.activity.getString(R.string.action_retry)

    @Test
    fun `the tail does not ask for pages by itself`() {
        val state = mutableStateOf(stateWithMore(nextPages = mapOf(ActivitySource.Orders to 1)))
        setContent(state)

        // Хвост на экране (список короче экрана — он и не уйдёт из него
        // никогда), но в сеть за это никто не полез.
        compose.onNodeWithText(loadMore).assertExists()
        assertEquals(NOTHING, events)

        // Курсор сдвинулся — так выглядит приехавшая страница. Именно на этом
        // держался старый автотриггер: эффект перезапускался и просил
        // следующую, потом ещё, и так до конца всех пяти источников.
        compose.runOnIdle {
            state.value = state.value.copy(nextPages = mapOf(ActivitySource.Orders to 2))
        }
        compose.waitForIdle()

        assertEquals(NOTHING, events)
    }

    @Test
    fun `one tap on the button is one request for a page`() {
        setContent(mutableStateOf(stateWithMore(nextPages = mapOf(ActivitySource.Orders to 1))))

        compose.onNodeWithText(loadMore).performClick()

        assertEquals(listOf(ActivityEvent.LoadMore), events)
    }

    @Test
    fun `the tail on an empty tab shows the button and still asks for nothing`() {
        // Пустая вкладка — единственное место, где страницы доливаются без
        // нажатия, и ведёт это ViewModel. Экран и здесь не имеет права просить
        // их сам: у пустого состояния хвост как раз в видимой области, то есть
        // старый автотриггер срабатывал бы тут первым делом.
        val state = mutableStateOf(
            stateWithMore(nextPages = mapOf(ActivitySource.Orders to 1), status = ActivityStatus.Completed),
        )
        setContent(state)

        compose.onNodeWithText(loadMore).assertExists()
        assertEquals(NOTHING, events)

        compose.runOnIdle {
            state.value = state.value.copy(nextPages = mapOf(ActivitySource.Orders to 2))
        }
        compose.waitForIdle()

        assertEquals(NOTHING, events)
    }

    @Test
    fun `an exhausted cursor leaves no tail at all`() {
        setContent(mutableStateOf(stateWithMore(nextPages = emptyMap())))

        // Иначе кнопка предлагала бы страницу, которой нет.
        compose.onNodeWithText(loadMore).assertDoesNotExist()
        assertEquals(NOTHING, events)
    }

    @Test
    fun `the button is busy while the page is in flight`() {
        // Иначе нетерпеливый тап отправил бы вторую страницу поверх первой:
        // сама ViewModel её отсечёт, но кнопка обязана показывать, что запрос
        // уже ушёл, — до issue #151 на этом месте была просто крутилка.
        setContent(
            mutableStateOf(
                stateWithMore(
                    nextPages = mapOf(ActivitySource.Orders to 1),
                    isLoadingMore = true,
                ),
            ),
        )

        compose.onNodeWithText(loadMore).assertIsNotEnabled()
        // Выключенная кнопка и занятая — разное: TalkBack должен сказать
        // «загрузка», а не молча прочитать надпись недоступной кнопки.
        compose.onNodeWithText(loadMore).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                compose.activity.getString(R.string.state_loading),
            ),
        )
        compose.onNodeWithText(loadMore).performClick()

        assertEquals(NOTHING, events)
    }

    @Test
    fun `a failed page turns the tail into a reason with a retry`() {
        setContent(
            mutableStateOf(
                stateWithMore(
                    nextPages = mapOf(ActivitySource.Orders to 1),
                    loadMoreFailure = ApiFailure(ApiError.NoConnection),
                ),
            ),
        )

        // «Показать ещё» без объяснения было бы предложением потыкать наугад.
        compose.onNodeWithText(loadMore).assertDoesNotExist()
        compose.onNodeWithText(retry).performClick()

        assertEquals(listOf(ActivityEvent.LoadMore), events)
    }

    private fun setContent(state: MutableState<ActivityState>) {
        compose.setContent {
            MahallaTheme {
                ActivityContentScreen(state = state.value, onEvent = { events += it })
            }
        }
        compose.waitForIdle()
    }

    /**
     * Состояние с хвостом. По умолчанию вкладка непустая (активность
     * `InProgress` попадает в «Faol»); [status] `Completed` даёт ту же вкладку
     * **пустой** — активность уезжает в «Tarix».
     */
    private fun stateWithMore(
        nextPages: Map<ActivitySource, Int>,
        isLoadingMore: Boolean = false,
        loadMoreFailure: ApiFailure? = null,
        status: ActivityStatus = ActivityStatus.InProgress,
    ) = ActivityState(
        items = ScreenState.Content(
            listOf(
                Activity(
                    id = "o-1",
                    source = ActivitySource.Orders,
                    kind = ActivityKind.FoodOrder,
                    status = status,
                    occurredAt = Instant.parse("2026-09-04T10:00:00Z"),
                    target = ActivityTarget.None,
                ),
            ),
        ),
        isLoadingMore = isLoadingMore,
        loadMoreFailure = loadMoreFailure,
        nextPages = nextPages,
    )

    private companion object {
        val NOTHING = emptyList<ActivityEvent>()
    }
}
