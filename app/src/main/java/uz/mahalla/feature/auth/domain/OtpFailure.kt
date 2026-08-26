package uz.mahalla.feature.auth.domain

import uz.mahalla.core.result.ApiError

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
}

/**
 * Раскладка HTTP-ответов бэкенда по причинам отказа.
 *
 * 401 здесь — это «код неверный», а не «сессия истекла»: на этапе
 * верификации сессии ещё нет.
 */
fun ApiError.asOtpFailure(): OtpFailure = when (this) {
    ApiError.Unauthorized -> OtpFailure.InvalidCode
    is ApiError.Http -> when (code) {
        HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> OtpFailure.InvalidCode
        HTTP_GONE -> OtpFailure.Expired
        HTTP_TOO_MANY_REQUESTS, HTTP_LOCKED -> OtpFailure.TooManyAttempts
        else -> OtpFailure.Network
    }
    else -> OtpFailure.Network
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_GONE = 410
private const val HTTP_LOCKED = 423
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
