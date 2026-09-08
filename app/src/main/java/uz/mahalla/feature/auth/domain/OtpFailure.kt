package uz.mahalla.feature.auth.domain

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure

/**
 * Почему код не подошёл (эпик 3.3). Отдельно от [ApiError], потому что
 * «неверный код» и «нет сети» — разные экраны поведения: в первом случае поле
 * подсвечивается и ввод продолжается, во втором показывается ошибка сети с
 * повтором.
 */
enum class OtpFailure {
    /** Код не совпал — можно вводить снова. */
    InvalidCode,

    /** Код истёк — нужен новый, кнопка повтора разблокируется. */
    Expired,

    /** Лимит попыток исчерпан — ввод блокируется до нового кода. */
    TooManyAttempts,

    /** Сеть, таймаут, 5xx — ошибка не про код. */
    Network,

    /**
     * Код приняли, но ответ пришёл про другой аккаунт (issue #86). Ввод
     * блокируется: новый код ничего не изменит, а входить надо не сюда.
     */
    ForeignAccount,
}

/**
 * Раскладка ответов бэкенда по причинам отказа.
 *
 * Классифицируем сначала по машинному коду из тела (issue #42): бэкенд отвечает
 * 400 и на истёкший код (`OTP_EXPIRED`), и на невалидный запрос
 * (`VALIDATION_ERROR`), так что одного HTTP-кода больше не хватает — по нему
 * «включите геолокацию» превращалось бы в «код неверный» с очисткой ввода.
 *
 * Коды сверены со стендом, но список бэкенда закрыт и будет расти, поэтому
 * после точных совпадений идёт разбор по ключевым словам, а в конце — прежняя
 * раскладка по HTTP-коду. 401 при верификации — это «код неверный», а не
 * «сессия истекла»: сессии на этом шаге ещё нет.
 */
fun ApiFailure.asOtpFailure(): OtpFailure {
    // Отказ, который выставил сам клиент, поймав чужой аккаунт: ответа сервера
    // за ним нет, и раскладка по кодам ниже приняла бы его за «нет сети».
    if (isForeignAccount()) return OtpFailure.ForeignAccount
    val code = server?.code?.trim()?.uppercase()
    return code?.let(::byServerCode) ?: byHttpError(error)
}

private fun byServerCode(code: String): OtpFailure? = when {
    code in VALIDATION_CODES -> OtpFailure.Network
    code.containsAny(EXPIRED_MARKERS) -> OtpFailure.Expired
    code.containsAny(ATTEMPTS_MARKERS) -> OtpFailure.TooManyAttempts
    code.containsAny(INVALID_CODE_MARKERS) -> OtpFailure.InvalidCode
    else -> null
}

private fun byHttpError(error: ApiError): OtpFailure = when (error) {
    ApiError.Unauthorized -> OtpFailure.InvalidCode
    is ApiError.Http -> when (error.code) {
        HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> OtpFailure.InvalidCode
        HTTP_GONE -> OtpFailure.Expired
        HTTP_TOO_MANY_REQUESTS, HTTP_LOCKED -> OtpFailure.TooManyAttempts
        else -> OtpFailure.Network
    }

    else -> OtpFailure.Network
}

private fun String.containsAny(markers: List<String>): Boolean = markers.any { contains(it) }

/**
 * Запрос не прошёл проверку — про сам код это не говорит ничего (обычно это
 * отсутствующие координаты или устройство). Ввод не стираем, показываем текст
 * сервера.
 */
private val VALIDATION_CODES = setOf("VALIDATION_ERROR", "BAD_REQUEST")

private val EXPIRED_MARKERS = listOf("EXPIRED")
private val ATTEMPTS_MARKERS = listOf("ATTEMPT", "TOO_MANY", "RATE", "COOLDOWN", "LOCK", "BLOCK")
private val INVALID_CODE_MARKERS = listOf("OTP", "CODE", "INVALID")

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_GONE = 410
private const val HTTP_LOCKED = 423
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
