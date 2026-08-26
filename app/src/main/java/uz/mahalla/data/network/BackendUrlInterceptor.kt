package uz.mahalla.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Переводит каждый запрос на адрес, заданный пользователем (issue #26).
 *
 * `Retrofit.baseUrl` фиксируется при сборке графа, а адрес бэкенда вводится в
 * рантайме — единственный способ учесть его без пересборки Retrofit'а
 * (и потери всех уже созданных API-интерфейсов) — переписать URL запроса.
 *
 * Интерцептор висит на обоих клиентах: авторизация и refresh обязаны ходить на
 * тот же сервер, что и остальные запросы.
 */
@Singleton
class BackendUrlInterceptor @Inject constructor(
    private val backendUrlStore: BackendUrlStore,
    @BaseUrl buildUrl: String,
) : Interceptor {

    /** Путь адреса сборки — его префикс срезается с пути запроса. */
    private val template = buildUrl.toHttpUrlOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = backendUrlStore.current.toHttpUrlOrNull()
        // Битый адрес в хранилище не должен ронять запросы: идём по адресу
        // сборки, а пользователь увидит ошибку сети и сможет исправить адрес.
        if (template == null || target == null) return chain.proceed(request)

        val rewritten = BackendUrl.rewrite(request.url, template, target)
        if (rewritten == request.url) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
