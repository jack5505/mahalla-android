package uz.mahalla.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Адрес бэкенда, введённый пользователем (issue #26).
 *
 * Чистая логика без Android и без сети: разбор строки из поля ввода и
 * подстановка адреса в готовый запрос. Retrofit фиксирует `baseUrl` при сборке
 * графа, поэтому сменить сервер в рантайме можно только переписав URL запроса —
 * это делает [BackendUrlInterceptor], а правила лежат здесь и проверяются
 * юнит-тестами.
 */
object BackendUrl {

    /** Схема, которую дописываем к `192.168.1.10:8080` — в локальной сети TLS нет. */
    private const val DEFAULT_SCHEME = "http://"

    private val SUPPORTED_SCHEMES = listOf("http://", "https://")

    /**
     * Приводит ввод к виду, который принимает Retrofit, или возвращает `null`,
     * если это не адрес.
     *
     * Что делаем с вводом:
     * - схему дописываем сами (пользователь набирает хост и порт);
     * - `query` и `fragment` срезаем: `baseUrl` с параметрами Retrofit склеит
     *   с эндпоинтом в бессмысленный URL;
     * - гарантируем завершающий `/` — без него Retrofit отбрасывает последний
     *   сегмент пути (`/api/v1` + `places` = `/api/places`).
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null

        val withScheme = when {
            SUPPORTED_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) } -> trimmed
            // Схема есть, но не http(s) — ws://, ftp:// и прочее бэкендом не бывает.
            trimmed.contains("://") -> return null
            else -> DEFAULT_SCHEME + trimmed
        }

        val url = withScheme.toHttpUrlOrNull() ?: return null
        val normalized = url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    /**
     * Переносит запрос с адреса сборки на адрес пользователя.
     *
     * Меняется не только хост: путь `baseUrl` у введённого адреса может быть
     * другим (`http://host:8080/` против `http://10.0.2.2:8080/api/v1/`).
     * Поэтому от пути запроса отрезается префикс [templateBase] и на его место
     * встаёт путь [targetBase]; query и fragment запроса сохраняются.
     */
    fun rewrite(requestUrl: HttpUrl, templateBase: HttpUrl, targetBase: HttpUrl): HttpUrl {
        val templateSegments = templateBase.encodedPathSegments.filter(String::isNotEmpty)
        val requestSegments = requestUrl.encodedPathSegments
        val relative = if (requestSegments.take(templateSegments.size) == templateSegments) {
            requestSegments.drop(templateSegments.size)
        } else {
            // Запрос пришёл не с шаблонного baseUrl (абсолютный @Url в Retrofit) —
            // путь оставляем как есть, иначе мы бы его молча испортили.
            requestSegments
        }

        val builder = requestUrl.newBuilder()
            .scheme(targetBase.scheme)
            .host(targetBase.host)
            .port(targetBase.port)
            .encodedPath("/")
        val segments = targetBase.encodedPathSegments.filter(String::isNotEmpty) + relative
        segments.forEach(builder::addEncodedPathSegment)
        return builder.build()
    }
}
