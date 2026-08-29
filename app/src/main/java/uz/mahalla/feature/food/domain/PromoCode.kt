package uz.mahalla.feature.food.domain

import uz.mahalla.core.result.ApiError

/**
 * Промокод (эпик 5.2). Проверяет его сервер — клиент только показывает
 * результат и считает скидку по тем же правилам, что и бэкенд, чтобы итог на
 * экране совпал с итогом в чеке.
 */
data class PromoCode(
    val code: String,
    val kind: PromoKind,
    /** Проценты для [PromoKind.Percent], сумы для [PromoKind.Fixed]. */
    val value: Long,
    /** Минимальная сумма позиций, с которой код работает. */
    val minOrderSum: Long = 0,
    /** Потолок скидки для процентных кодов; `null` — без потолка. */
    val maxDiscountSum: Long? = null,
) {

    /**
     * Скидка от суммы позиций. Процент округляется **вниз** до целых сум —
     * округление вверх дало бы клиенту лишнюю суму за счёт заведения, и на
     * тысяче заказов это расхождение с бэкендом заметит бухгалтерия.
     */
    fun discountFor(subtotalSum: Long): Long {
        if (subtotalSum < minOrderSum) return 0
        val raw = when (kind) {
            PromoKind.Percent -> subtotalSum * value.coerceIn(0, PERCENT_MAX) / PERCENT_MAX
            PromoKind.Fixed -> value
        }
        val capped = maxDiscountSum?.let { minOf(raw, it) } ?: raw
        return capped.coerceIn(0, subtotalSum)
    }

    private companion object {
        const val PERCENT_MAX = 100L
    }
}

enum class PromoKind { Percent, Fixed }

/** Состояние поля промокода на экране корзины. */
sealed interface PromoState {
    data object Idle : PromoState
    data object Checking : PromoState
    data class Applied(val promo: PromoCode) : PromoState
    data class Rejected(val reason: PromoFailure) : PromoState
}

/** Почему код не сработал. Каждый вариант — свой текст на экране. */
sealed interface PromoFailure {
    data object NotFound : PromoFailure
    data object Expired : PromoFailure
    data class MinOrder(val minOrderSum: Long) : PromoFailure
    data object Network : PromoFailure
}

/**
 * HTTP-коды промокода. 404 — кода нет, 410 — истёк, 409/422 — не подходит под
 * заказ (минимальная сумма). Всё остальное — сетевая ошибка: винить в ней
 * введённый код нельзя, человек начнёт переписывать правильные буквы.
 */
fun ApiError.asPromoFailure(minOrderSum: Long = 0): PromoFailure = when (this) {
    ApiError.NotFound -> PromoFailure.NotFound
    is ApiError.Http -> when (code) {
        HTTP_GONE -> PromoFailure.Expired
        HTTP_CONFLICT, HTTP_UNPROCESSABLE -> PromoFailure.MinOrder(minOrderSum)
        else -> PromoFailure.Network
    }

    else -> PromoFailure.Network
}

private const val HTTP_GONE = 410
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
