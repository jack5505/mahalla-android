package uz.mahalla.core.crash

/**
 * Вычистка секретов из строки, уезжающей в отчёт о падении (issue #74).
 *
 * Отдельно от Sentry и от Android: это чистые функции, и именно они —
 * единственное, что стоит между токеном пользователя и чужим сервером. Их и
 * проверяют тесты.
 *
 * Чего здесь нет и быть не может: гарантии, что секрет не уедет вообще. Сообщение
 * исключения пишет тот, кто его бросил, и предугадать все формы нельзя. Поэтому
 * защита двухслойная — сначала в отчёт не кладут лишнего
 * ([CrashReporter] не принимает произвольных данных, тела ответов не
 * прикладываются вовсе), и только потом сюда.
 */
object SecretScrubber {

    /** Чем заменяется вырезанное. Видно, что поле было, но не видно значения. */
    const val REDACTED = "[REDACTED]"

    /**
     * Заголовки, которые не должны попасть в отчёт. Тот же список, что
     * редактируется в logcat ([uz.mahalla.data.network.NetworkFactory]) и в
     * инспекторе трафика
     * ([uz.mahalla.data.network.inspector.ChuckerHttpInspector]), плюс
     * `X-Session-Id`: по нему завершают чужую сессию (issue #61).
     */
    val SECRET_HEADERS: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-session-id",
    )

    /**
     * Имена полей и query-параметров, значение которых вырезается. Совпадение —
     * по вхождению подстроки в нижнем регистре: `otpToken`, `refresh_token` и
     * `X-Refresh-Token` должны ловиться одним правилом.
     */
    private val SECRET_NAME_PARTS = listOf(
        "token",
        "authorization",
        "cookie",
        "password",
        "secret",
        "pin",
        "otp",
        "dsn",
        "apikey",
        "api_key",
        "credential",
        "signature",
    )

    /** `Bearer eyJhbGciOi…` — самая частая форма утечки токена в сообщении. */
    private val BEARER = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/=]{8,}""")

    /** JWT: три base64url-сегмента через точку. Так выглядят оба токена сессии. */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""")

    /**
     * `token=…`, `otpCode: 123456`, `"pin":"0000"` — секрет, названный по имени
     * прямо в тексте. Значение обрывается на разделителе, чтобы не съесть
     * остаток сообщения.
     */
    private val NAMED_SECRET = Regex(
        """(?i)(["']?[a-z0-9_.\[\]-]*(?:token|password|passwd|secret|otpcode|pin)["']?\s*[=:]\s*)["']?[^\s,;&"'}\])]+""",
    )

    /** Секретный ли это заголовок или поле по имени. */
    fun isSecretName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in SECRET_HEADERS) return true
        return SECRET_NAME_PARTS.any { lower.contains(it) }
    }

    /**
     * Текст сообщения без токенов. `null` остаётся `null`.
     *
     * Порядок правил не косметика: [NAMED_SECRET] идёт первым и режет значение
     * целиком по имени поля, а [BEARER] и [JWT] добирают то, что названо не
     * было. Обратный порядок оставлял бы `token=[REDACTED]]` — уже вырезанное
     * значение снова попадало бы под правило.
     */
    fun scrubText(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        return text
            .replace(NAMED_SECRET) { match -> "${match.groupValues[1]}$REDACTED" }
            .replace(BEARER) { match -> "${match.groupValues[1]} $REDACTED" }
            .replace(JWT, REDACTED)
    }

    /**
     * URL без секретов в query: `?otpToken=…` вырезается, остальные параметры
     * остаются — по ним и понятно, какой запрос упал.
     *
     * Разбор строкой, а не `URI`: в отчёт приезжает и то, что URI не разберёт
     * (обрезанный адрес, шаблон Retrofit), а падать здесь нельзя.
     */
    fun scrubUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        val separator = url.indexOfFirst { it == '?' }
        if (separator < 0) return scrubText(url)
        val path = url.substring(0, separator)
        val query = url.substring(separator + 1)
        return "$path?${scrubQuery(query)}"
    }

    /** Строка query (`a=1&token=…`) с вырезанными значениями секретных параметров. */
    fun scrubQuery(query: String?): String? {
        if (query.isNullOrEmpty()) return query
        return query.split('&').joinToString("&") { parameter ->
            val name = parameter.substringBefore('=')
            if (parameter.contains('=') && isSecretName(name)) "$name=$REDACTED" else parameter
        }
    }

    /**
     * Карта заголовков или полей: секретные значения заменяются, остальные
     * проходят через [scrubText] — токен встречается и в значении несекретного
     * на вид поля.
     */
    fun scrubMap(values: Map<String, String>?): Map<String, String>? {
        if (values == null) return null
        return values.mapValues { (name, value) ->
            if (isSecretName(name)) REDACTED else scrubText(value).orEmpty()
        }
    }
}
