package uz.mahalla.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uz.mahalla.core.result.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка адреса бэкенда перед сохранением (issue #26).
 *
 * Опечатка в хосте или порту иначе выясняется только на экране входа, где
 * ошибка выглядит как «сеть недоступна» и адрес уже сохранён.
 *
 * Интерфейс — чтобы ViewModel экрана тестировалась без сети.
 */
interface BackendReachability {

    /**
     * Отвечает ли сервер по адресу.
     *
     * Успех — **любой** HTTP-ответ, включая 404 и 401: корневой путь может
     * ничего не отдавать, но это уже разговор с сервером. Провал — только
     * отсутствие ответа: неизвестный хост, отказ соединения, таймаут.
     */
    suspend fun check(baseUrl: String): Boolean
}

@Singleton
class OkHttpBackendReachability @Inject constructor() : BackendReachability {

    private companion object {
        /** Пользователь ждёт ответа на экране — держать его 15 секунд нельзя. */
        const val TIMEOUT_SECONDS = 5L
    }

    /**
     * Свой клиент, а не общий: на общем висит [BackendUrlInterceptor], и
     * проверка уходила бы на текущий адрес вместо проверяемого. Создаётся
     * лениво — проверка случается раз в установку.
     */
    private val client by lazy {
        NetworkFactory.clientBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun check(baseUrl: String): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                // HEAD: тело корневого пути может быть большим, а нужен сам факт ответа.
                client.newCall(Request.Builder().url(url).head().build())
                    .execute()
                    .use { true }
            }.getOrDefault(false)
        }
    }
}
