package uz.mahalla.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import uz.mahalla.data.prefs.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Подставляет `Authorization: Bearer …` во все запросы, где заголовка ещё
 * нет (эпик 1.3). Без сессии запрос уходит как есть — публичные эндпоинты
 * (каталог, отправка OTP) работают анонимно.
 *
 * `runBlocking` здесь уместен: интерсептор уже выполняется на пуле OkHttp,
 * а не на Main.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_AUTHORIZATION) != null) {
            return chain.proceed(request)
        }
        val accessToken = runBlocking { sessionStore.current() }?.accessToken
            ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header(HEADER_AUTHORIZATION, bearer(accessToken))
                .build(),
        )
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "

        fun bearer(token: String): String = BEARER_PREFIX + token
    }
}
