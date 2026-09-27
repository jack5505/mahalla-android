package uz.mahalla.core.result

/**
 * Единая доменная ошибка сетевого слоя (эпик 1.3). UI не знает ни про
 * Retrofit, ни про HTTP-коды — только про эти варианты.
 */
sealed interface ApiError {
    /** Нет сети / DNS / соединение оборвалось. */
    data object NoConnection : ApiError

    /** Сервер не ответил за таймаут. */
    data object Timeout : ApiError

    /** 401 — токен невалиден и refresh не помог. */
    data object Unauthorized : ApiError

    /**
     * 403 — доступ запрещён. [code] — машинный код бэкенда, если он был в
     * теле ответа (issue #344): `GEO_PERMISSION_REQUIRED`/
     * `GEO_INVALID_COORDINATES` — гео обязательно для запроса, это
     * единственные коды с отдельной обработкой ([isGeo]). Бэкенд вешает код и
     * на другие причины отказа — «не ваше заведение» (`PLACE_FORBIDDEN`),
     * блокировку кошелька (`WALLET_BLOCKED`) — их здесь по отдельности не
     * разбираем: подробности и почему — `docs/API-CONTRACT.md`. `null` —
     * сервер не объяснил ничего, это и есть «нет прав на это действие».
     */
    data class Forbidden(val code: String? = null) : ApiError {
        /** Гео обязательно для запроса — включить и попробовать снова. */
        val isGeo: Boolean get() = code == GEO_PERMISSION_REQUIRED || code == GEO_INVALID_COORDINATES

        companion object {
            const val GEO_PERMISSION_REQUIRED = "GEO_PERMISSION_REQUIRED"
            const val GEO_INVALID_COORDINATES = "GEO_INVALID_COORDINATES"
        }
    }

    /** 404 — ресурс не найден. */
    data object NotFound : ApiError

    /** Прочие HTTP-ошибки, включая 5xx. */
    data class Http(val code: Int, val message: String?) : ApiError

    /**
     * HTTP-код успешный, но конверт бэкенда говорит `success: false`.
     * [code] — машинный код из тела (`OTP_EXPIRED`, `VALIDATION_ERROR`);
     * текст для человека лежит в [ServerError.message], как и у HTTP-отказов.
     */
    data class Business(val code: String?) : ApiError

    /** Тело ответа не разобралось: битый JSON или несовпадение схемы. */
    data object Serialization : ApiError

    /** Всё остальное — баг, а не ожидаемый сценарий. */
    data class Unexpected(val cause: Throwable?) : ApiError

    companion object {
        fun fromHttpCode(code: Int, serverCode: String? = null, message: String? = null): ApiError =
            when (code) {
                401 -> Unauthorized
                403 -> Forbidden(serverCode)
                404 -> NotFound
                else -> Http(code, message)
            }
    }
}
