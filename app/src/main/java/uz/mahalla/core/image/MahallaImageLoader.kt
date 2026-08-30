package uz.mahalla.core.image

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.OkHttpClient
import uz.mahalla.data.network.BackendUrlStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coil-интерцептор, дописывающий относительной ссылке адрес бэкенда
 * (issue #60).
 *
 * Почему интерцептор, а не преобразование в маппере данных: адрес сервера
 * пользователь может сменить в любой момент (issue #26), а ссылка к этому
 * времени уже лежит в Room и в состоянии экрана. Подставлять хост нужно в
 * момент загрузки — тогда после переезда стенда картинки поедут туда же, куда
 * и запросы.
 */
@Singleton
class BackendImageUrlInterceptor @Inject constructor(
    private val backendUrlStore: BackendUrlStore,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data !is String) return chain.proceed(chain.request)

        val resolved = ImageUrl.resolve(backendUrlStore.current, data)
        // Ссылка не приводится к загружаемому виду — пропускаем как есть:
        // Coil вернёт ошибку, а компонент нарисует фоллбэк-иконку.
        if (resolved == null || resolved == data) return chain.proceed(chain.request)

        return chain.proceed(chain.request.newBuilder().data(resolved).build())
    }
}

/**
 * Сборка [ImageLoader] (issue #60). Вынесена из DI-модуля по той же причине,
 * что и [uz.mahalla.data.network.NetworkFactory]: конфигурацию проверяет
 * обычный тест, а не его копия.
 */
object MahallaImageLoader {

    /** Заметно, но не «выплывает»: рисуем поверх скелетона. */
    const val CROSSFADE_MS: Int = 200

    private const val MEMORY_CACHE_PERCENT = 0.20
    private const val DISK_CACHE_DIR = "image_cache"
    private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

    fun create(
        context: Context,
        callFactory: () -> Call.Factory,
        backendImageUrlInterceptor: Interceptor,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(backendImageUrlInterceptor) }
        // Клиент собирается лениво: граф сети поднимается под держащимся
        // splash'ем, и тянуть его ради ещё не запрошенной картинки незачем.
        .callFactory(callFactory)
        .crossfade(CROSSFADE_MS)
        .memoryCache {
            MemoryCache.Builder(context).maxSizePercent(MEMORY_CACHE_PERCENT).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        // Бэкенд отдаёт медиа без заголовков кэширования, а фото заведения
        // между двумя открытиями экрана не меняется: без этого дисковый кэш
        // не использовался бы вовсе.
        .respectCacheHeaders(false)
        .build()

    /**
     * Клиент для картинок: тот же пул соединений и то же доверие сертификату
     * стенда (issue #32), но **без** интерцепторов приложения.
     *
     * Снимается ровно три вещи, и каждая по делу: `Authorization` — потому что
     * ссылка на картинку ведёт куда угодно, и Bearer не должен уезжать на
     * чужой хост; подстановка адреса и гео-заголовки — потому что ссылка уже
     * абсолютная; инспектор трафика — потому что лента из сотни картинок
     * вытеснит из него настоящие запросы. `TokenAuthenticator` тоже снят: 401
     * на картинку не повод обновлять сессию.
     */
    fun imageClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .apply { interceptors().clear() }
        .authenticator(Authenticator.NONE)
        .build()
}
