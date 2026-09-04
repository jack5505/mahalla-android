package uz.mahalla.core.image

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.prefs.SettingsDataStore
import java.io.File

/**
 * Загрузчик изображений (issue #60): чем ходит в сеть и как достраивает
 * ссылки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MahallaImageLoaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `image client keeps the pool but drops app interceptors and authenticator`() {
        val appClient = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request()) }
            .authenticator { _, _ -> null }
            .build()

        val imageClient = MahallaImageLoader.imageClient(appClient)

        assertTrue("Bearer не должен уезжать на чужой хост", imageClient.interceptors.isEmpty())
        assertSame("401 на картинку не повод обновлять сессию", Authenticator.NONE, imageClient.authenticator)
        assertSame("соединения общие с остальным приложением", appClient.connectionPool, imageClient.connectionPool)
    }

    @Test
    fun `loader is built with the backend url interceptor`() = runTest {
        val interceptor = BackendImageUrlInterceptor(store())

        val loader = MahallaImageLoader.create(
            context = context,
            callFactory = { OkHttpClient() },
            backendImageUrlInterceptor = interceptor,
        )

        assertTrue(loader.components.interceptors.contains(interceptor))
    }

    @Test
    fun `relative url gets the current backend address`() = runTest {
        val store = store()
        store.save(SAVED_URL)
        val chain = RecordingChain(request("/media/entity/42.jpg"))

        BackendImageUrlInterceptor(store).intercept(chain)

        assertEquals("http://192.168.0.10:9090/media/entity/42.jpg", chain.proceeded?.data)
    }

    @Test
    fun `absolute url reaches the loader untouched`() = runTest {
        val chain = RecordingChain(request("https://cdn.example.com/a.jpg"))

        BackendImageUrlInterceptor(store()).intercept(chain)

        assertSame("лишняя пересборка запроса рвала бы кэш по ключу", chain.request, chain.proceeded)
    }

    @Test
    fun `non string data is none of the interceptor's business`() = runTest {
        // Иконка из ресурсов или готовый Drawable адреса бэкенда не требуют.
        val chain = RecordingChain(request(android.R.drawable.ic_menu_gallery))

        BackendImageUrlInterceptor(store()).intercept(chain)

        assertSame(chain.request, chain.proceeded)
    }

    private fun request(data: Any): ImageRequest =
        ImageRequest.Builder(context).data(data).build()

    private fun store(): BackendUrlStore = BackendUrlStore(
        SettingsDataStore(newDataStore()),
        BUILD_URL,
        true,
    )

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "image-loader.preferences_pb") },
    )

    /**
     * Цепочка Coil, запоминающая запрос, который до неё доехал.
     *
     * `withRequest` помечен `@ExperimentalCoilApi` в самой библиотеке —
     * реализовать интерфейс без opt-in нельзя, а обойти нечем: другого способа
     * подсунуть интерцептору цепочку у Coil нет.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private class RecordingChain(override val request: ImageRequest) : Interceptor.Chain {

        var proceeded: ImageRequest? = null
            private set

        override val size: Size = Size.ORIGINAL

        override fun withSize(size: Size): Interceptor.Chain = this

        override fun withRequest(request: ImageRequest): Interceptor.Chain = this

        override suspend fun proceed(request: ImageRequest): ImageResult {
            proceeded = request
            return ErrorResult(drawable = null, request = request, throwable = NOT_LOADED)
        }

        private companion object {
            val NOT_LOADED = IllegalStateException("тест не грузит картинку")
        }
    }

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"
        const val SAVED_URL = "http://192.168.0.10:9090/api/v1/"
    }
}
