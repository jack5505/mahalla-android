package uz.mahalla.feature.food.domain

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import java.util.Locale

/**
 * Промокод (эпик 5.2), проверенный сервером (`GET promotions/check`, issue #63).
 *
 * Скидку считает бэкенд и присылает готовым числом, а не правилом: повторять у
 * себя проценты, потолки и минимальные суммы значит однажды разойтись с чеком.
 * Поэтому [discountSum] привязана к той сумме позиций, с которой код
 * проверяли ([checkedSubtotalSum]) — для другой корзины она недействительна.
 */
data class PromoCode(
    val code: String,
    val discountSum: Long,
    val checkedSubtotalSum: Long,
) {

    /** Состав корзины изменился — прежний ответ сервера к нему не относится. */
    fun isStaleFor(subtotalSum: Long): Boolean = subtotalSum != checkedSubtotalSum

    /**
     * Скидка от суммы позиций. Для изменившейся корзины — ноль: показать старую
     * скидку на новом составе значит назвать сумму, которой не будет в счёте.
     */
    fun discountFor(subtotalSum: Long): Long =
        if (isStaleFor(subtotalSum)) 0 else discountSum.coerceIn(0, subtotalSum)
}

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

    /** Код есть и не истёк, но к этому заказу не применяется (`valid: false`). */
    data object NotApplicable : PromoFailure
    data object Network : PromoFailure
}

/**
 * Классификация отказа `promotions/check`.
 *
 * Сначала смотрим машинный код бэкенда: на неизвестный код стенд отвечает
 * `404 NOT_FOUND` («Promo-kod topilmadi»), но 404 приходит и от общих
 * фильтров, а по одному HTTP-коду «кода нет» от «ручки нет» не отличить.
 * Всё непонятое — сетевая ошибка: винить в ней введённый код нельзя, человек
 * начнёт переписывать правильные буквы.
 */
fun ApiFailure.asPromoFailure(): PromoFailure {
    val code = server?.code?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when {
        code.contains("EXPIRE") || code.contains("INACTIVE") -> PromoFailure.Expired
        code.contains("NOT_FOUND") -> PromoFailure.NotFound
        code.contains("LIMIT") || code.contains("MIN_ORDER") -> PromoFailure.NotApplicable
        error == ApiError.NotFound -> PromoFailure.NotFound
        error is ApiError.Http && error.code == HTTP_GONE -> PromoFailure.Expired
        error is ApiError.Http && error.code in NOT_APPLICABLE_CODES -> PromoFailure.NotApplicable
        else -> PromoFailure.Network
    }
}

private const val HTTP_GONE = 410
private val NOT_APPLICABLE_CODES = setOf(409, 422)
