package uz.mahalla.core.result

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/**
 * Разбор тела ошибки бэкенда (issue #34).
 *
 * Схема ответа не фиксирована намеренно: у самого бэкенда Mahalla это
 * `{"success": false, "error": {"code": …, "message": …}}`, у nginx перед ним —
 * HTML, у Spring Security — `{"error": "unauthorized", "message": …}`. Поэтому
 * тело не декодируется в DTO (одно несовпадение поля — и текст потерян
 * целиком), а обходится как дерево: берётся первый непустой текст из известных
 * ключей, остальное показывается человеку как есть.
 *
 * Разбор ничего не кидает: ошибка при чтении ошибки — худшее место для падения.
 */
object ServerErrorParser {

    fun parse(exception: HttpException): ServerError {
        val response = exception.response()
        val request = response?.raw()?.request
        return parse(
            httpCode = exception.code(),
            httpMessage = exception.message().takeIf { it.isNotBlank() },
            requestLine = request?.let { "${it.method} ${it.url}" },
            // Retrofit уже забуферил тело ошибки, но `string()` всё равно
            // работает с потоком — сеть могла оборваться на середине.
            body = runCatchingCancellable { response?.errorBody()?.string() }.getOrNull(),
        )
    }

    fun parse(
        httpCode: Int,
        httpMessage: String? = null,
        requestLine: String? = null,
        body: String? = null,
    ): ServerError {
        val trimmed = body?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = trimmed?.let {
            runCatchingCancellable { Json.parseToJsonElement(it) }.getOrNull()
        }
        // Одиночный литерал разбором не считаем: `Service unavailable` парсер
        // принимает как строку, и тело уехало бы на экран в кавычках вместо
        // того, чтобы стать текстом ошибки.
        val json = parsed?.takeIf { it is JsonObject || it is JsonArray }
        val containers = (json as? JsonObject)?.containers().orEmpty()

        return ServerError(
            httpCode = httpCode,
            httpMessage = httpMessage,
            code = containers.firstNotNullOfOrNull { it.text(CODE_KEYS, stringsOnly = false) },
            message = containers.firstNotNullOfOrNull { it.text(MESSAGE_KEYS) }
                ?: trimmed?.takeIf { json == null && it.isPlainSentence() },
            requestLine = requestLine,
            body = json?.let { prettyOrNull(it) }?.truncate() ?: trimmed?.truncate(),
        )
    }

    /**
     * Где искать поля: сначала во вложенном `error`, потом в корне. Порядок
     * важен — в конверте Mahalla корневой `message` может отсутствовать, а в
     * плоских ответах вложенного объекта нет.
     */
    private fun JsonObject.containers(): List<JsonObject> =
        listOfNotNull(this["error"] as? JsonObject, this["data"] as? JsonObject, this)

    private fun JsonObject.text(keys: List<String>, stringsOnly: Boolean = true): String? =
        keys.firstNotNullOfOrNull { key ->
            val value = this[key]
            if (value !is JsonPrimitive || value === JsonNull) return@firstNotNullOfOrNull null
            if (stringsOnly && !value.isString) return@firstNotNullOfOrNull null
            value.content.trim().takeIf { it.isNotEmpty() }
        }

    /**
     * JSON показывается с отступами — сплошная строка в 40 полей нечитаема, а
     * смотрят на неё именно затем, чтобы прочитать.
     */
    private fun prettyOrNull(element: JsonElement): String? = runCatchingCancellable {
        PRETTY.encodeToString(JsonElement.serializer(), element)
    }.getOrNull()

    /**
     * Не-JSON тело идёт в текст ошибки только если это похоже на фразу:
     * страницу nginx, обрывок JSON и стектрейс показывать вместо сообщения
     * нельзя — они остаются в подробностях.
     */
    private fun String.isPlainSentence(): Boolean =
        length <= MAX_MESSAGE_CHARS &&
            first() !in MARKUP_STARTS &&
            !contains("\n\tat ")

    private fun String.truncate(): String =
        if (length <= MAX_BODY_CHARS) this else take(MAX_BODY_CHARS) + "…"

    private val PRETTY = Json { prettyPrint = true }

    private val MESSAGE_KEYS = listOf(
        "message",
        "error_description",
        "errorDescription",
        "detail",
        "title",
        "reason",
        // Последним: в плоских ответах здесь текст, а в конверте — объект,
        // который до этой ветки уже разобран как контейнер.
        "error",
    )

    private val CODE_KEYS = listOf("code", "error_code", "errorCode")

    /** С этого начинается разметка или битый JSON, а не фраза для человека. */
    private val MARKUP_STARTS = charArrayOf('<', '{', '[')

    /** Тело ответа держим в памяти состояния экрана — на экран влезет меньше. */
    private const val MAX_BODY_CHARS = 2_000

    private const val MAX_MESSAGE_CHARS = 300
}
