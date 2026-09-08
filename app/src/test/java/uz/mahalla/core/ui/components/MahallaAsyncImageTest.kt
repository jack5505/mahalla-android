package uz.mahalla.core.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uz.mahalla.ui.theme.MahallaTheme

/**
 * Сетевая картинка кита (issue #137): уходит ли запрос и видно ли результат.
 *
 * Тест на композицию, а не на ViewModel, потому что баг был именно в дереве:
 * `Image(painter)` рисовался только в состоянии `Success`, а Coil 2.x, если
 * размер не задан явно, берёт его из `DrawScope` — painter, которого нет в
 * отрисовке, размера не получает, запрос не выполняется, состояние навсегда
 * `Loading`. Тесты уровня данных этого не видели и увидеть не могли: маппер
 * отдавал правильные ссылки, интерцептор правильно дописывал хост, а в сеть
 * не уходило ничего.
 *
 * Отсюда две проверки, и обе нужны: запрос дошёл до загрузчика с размером
 * места под фото — и загруженное фото действительно нарисовано, а не закрыто
 * скелетоном сверху.
 *
 * Плотность экрана зафиксирована (`mdpi`), чтобы dp и px совпадали и размер
 * запроса можно было сверить с вёрсткой числом, а не «больше нуля».
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MahallaAsyncImageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val interceptor = RecordingInterceptor()

    @Before
    fun setUp() {
        // Синглтон Coil: `rememberAsyncImagePainter` берёт загрузчик из
        // `LocalImageLoader`, а тот падает на `context.imageLoader`.
        Coil.setImageLoader(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext<Application>())
                // Кэши в тесте не нужны: интерцептор замыкает цепочку раньше,
                // а дисковый ещё и полез бы в файловую систему.
                .memoryCache(null)
                .diskCache(null)
                .components { add(interceptor) }
                .build(),
        )
    }

    @After
    fun tearDown() {
        // Robolectric переиспользует classloader между тестовыми классами —
        // без сброса подменённый загрузчик утёк бы в чужие тесты.
        Coil.reset()
    }

    @Test
    fun `photo url reaches the image loader with the size it is drawn at`() {
        setContentWith(PHOTO_URL)

        drawFrame()

        assertEquals(
            "запрос не дошёл до загрузчика: картинка вне отрисовки не получает размера",
            1,
            interceptor.executed.size,
        )
        val executed = interceptor.executed.first()
        assertEquals(PHOTO_URL, executed.request.data)
        assertEquals(
            "размер запроса — размер места под фото, иначе Coil сэмплирует не туда",
            Size(WIDTH, HEIGHT),
            executed.size,
        )
    }

    @Test
    fun `loaded photo is visible and not covered by the skeleton`() {
        setContentWith(PHOTO_URL)

        // Первый кадр запускает запрос — из него Coil берёт размер; второй
        // рисует уже пришедшую картинку.
        drawFrame().recycle()
        val frame = drawFrame()

        assertTrue(
            "загруженного фото не видно: сверху остался скелетон или фоллбэк",
            frame.containsPixel(PHOTO_COLOR),
        )
        frame.recycle()
    }

    @Test
    fun `blank url is not a request`() {
        setContentWith("   ")

        drawFrame().recycle()

        assertTrue("пустого запроса в сеть быть не должно", interceptor.executed.isEmpty())
    }

    private fun setContentWith(url: String) {
        compose.setContent {
            MahallaTheme {
                MahallaAsyncImage(
                    url = url,
                    contentDescription = null,
                    modifier = Modifier.size(width = WIDTH.dp, height = HEIGHT.dp),
                )
            }
        }
    }

    /**
     * Кадр целиком: измерить, разложить и **нарисовать** окно; возвращает то,
     * что нарисовалось.
     *
     * Robolectric сам обход иерархии не выполняет — после `waitForIdle()`
     * композиция есть, а отрисовки нет, и запрос картинки не начался бы даже
     * на исправном коде. Поэтому кадр здесь рисуется руками, в холст поверх
     * bitmap'а (отсюда `GraphicsMode.NATIVE`).
     */
    private fun drawFrame(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        view.measure(exactly(SCREEN_WIDTH), exactly(SCREEN_HEIGHT))
        view.layout(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
        val bitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        compose.waitForIdle()
        return bitmap
    }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    /**
     * Есть ли на кадре хоть один пиксель ровно этого цвета. «Ровно» — важно:
     * скелетон и фоллбэк непрозрачны не полностью, поэтому поверх картинки они
     * дают смешанный цвет, а не исходный.
     */
    private fun Bitmap.containsPixel(color: Int): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { it == color }
    }

    /**
     * Загрузчик, который никуда не ходит: запоминает запрос и сразу отдаёт
     * однотонную картинку. Возврат результата из интерцептора замыкает цепочку
     * до fetcher'а — сети в юнит-тесте нет и не должно быть.
     */
    private class RecordingInterceptor : Interceptor {

        private val requests = mutableListOf<Executed>()

        val executed: List<Executed> get() = requests.toList()

        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            requests += Executed(chain.request, chain.size)
            return SuccessResult(
                drawable = ColorDrawable(PHOTO_COLOR),
                request = chain.request,
                // Именно MEMORY_CACHE: на этом источнике Coil не проигрывает
                // crossfade, и картинка сразу рисуется в полную непрозрачность.
                // Иначе тесту пришлось бы двигать ещё и системные часы —
                // CrossfadePainter считает время по `SystemClock`, а не по
                // тестовым часам композиции.
                dataSource = DataSource.MEMORY_CACHE,
            )
        }

        class Executed(val request: ImageRequest, val size: Size)
    }

    private companion object {
        const val PHOTO_URL = "https://cdn.example.com/place/42.jpg"

        /** Цвет «фотографии»: на экране приложения такого нет ни у чего. */
        const val PHOTO_COLOR = Color.RED

        const val WIDTH = 160
        const val HEIGHT = 120
        const val SCREEN_WIDTH = 393
        const val SCREEN_HEIGHT = 852
    }
}
