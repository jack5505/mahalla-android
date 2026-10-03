package uz.mahalla.feature.business.domain

import java.time.Instant

/**
 * Заявка на вывод с бизнес-кошелька (issue #290, бэкенд jack5505/mahalla#223).
 *
 * @param amountSum сумма заявки, целые сумы (тийины бэкенда переведены на
 * границе данных, как и везде в кошельке, issue #149).
 * @param cardMasked последние четыре цифры карты вида «•••• 4400». Полный
 * номер сервер присылает обратно тем же полем, что принял, но эхо PAN на
 * экране не показывается — оно там и не нужно: сумма и хвост карты хватает,
 * чтобы узнать свою заявку.
 * @param status статус заявки на момент ответа. Отдельной ручки для его
 * отслеживания у бэкенда нет (`docs/API-CONTRACT.md`) — это то, что вернул
 * именно этот запрос.
 */
data class Payout(
    val id: String,
    val amountSum: Long,
    val cardMasked: String?,
    val status: PayoutStatus,
    val createdAt: Instant?,
)

/**
 * Статус заявки на вывод. Написание бэкенд не фиксирует (в схеме — `string`),
 * поэтому принимаются распространённые варианты; незнакомое значение —
 * [Unknown], и тогда экран говорит «заявка отправлена» вместо того, чтобы
 * угадывать исход (то же правило, что у [uz.mahalla.feature.wallet.domain.TransactionStatus]).
 */
enum class PayoutStatus {
    Pending,
    Approved,
    Completed,
    Rejected,
    Unknown,
    ;

    companion object {

        private val PENDING = setOf("PENDING", "PROCESSING", "IN_PROGRESS", "CREATED", "NEW")
        private val APPROVED = setOf("APPROVED", "ACCEPTED", "IN_TRANSFER")
        private val COMPLETED = setOf("COMPLETED", "SUCCESS", "SUCCEEDED", "DONE", "PAID")
        private val REJECTED = setOf("REJECTED", "DECLINED", "CANCELLED", "CANCELED", "FAILED")

        fun fromServer(value: String?): PayoutStatus =
            when (value?.trim()?.uppercase().orEmpty()) {
                in PENDING -> Pending
                in APPROVED -> Approved
                in COMPLETED -> Completed
                in REJECTED -> Rejected
                else -> Unknown
            }
    }
}

/**
 * Черновик заявки на вывод.
 *
 * Минимальной суммы и комиссии здесь нет намеренно (issue #290): в схеме
 * `PayoutCreateRequest` объявлен только технический минимум в одну тийину, а
 * настоящих бизнес-чисел (процент комиссии, минимальная сумма вывода) в
 * контракте нет вовсе. Придумать их на клиенте значило бы показать цифру,
 * которую бэкенд не подтверждал, — вместо неё сумму и номер карты проверяет
 * сам сервер, а отказ показывается его текстом ([PayoutValidator] проверяет
 * только то, что известно точно: сумма положительная и целая, карта — 16
 * цифр по регэкспу самой схемы).
 */
data class PayoutDraft(
    val amountText: String = "",
    val cardNumberText: String = "",
) {
    val amountSum: Long? get() = parsePayoutAmount(amountText)
    val cardDigits: String get() = cardNumberText.filter(Char::isDigit)
}

/** Почему заявку нельзя отправить. Одна причина на поле (issue #84). */
enum class PayoutError {
    AmountRequired,
    CardNumberInvalid,
}

object PayoutValidator {

    /** Ровно столько цифр требует `PayoutCreateRequest.cardNumber` (`\d{16}`). */
    const val CARD_DIGITS = 16

    fun validate(draft: PayoutDraft): Set<PayoutError> {
        val errors = mutableSetOf<PayoutError>()
        if (draft.amountSum == null) errors += PayoutError.AmountRequired
        if (draft.cardDigits.length != CARD_DIGITS) errors += PayoutError.CardNumberInvalid
        return errors
    }
}

/** Столько цифр не наберётся ни в одной осмысленной сумме — страховка от мусора во вводе. */
private const val MAX_AMOUNT_DIGITS = 12

private fun parsePayoutAmount(raw: String): Long? {
    val digits = raw.filter(Char::isDigit)
    if (digits.isEmpty() || digits.length > MAX_AMOUNT_DIGITS) return null
    return digits.toLongOrNull()?.takeIf { it > 0 }
}

/** «•••• 4400» — только хвост, полный номер на экране не остаётся. */
fun maskCardNumber(cardNumber: String?): String? {
    val digits = cardNumber?.filter(Char::isDigit).orEmpty()
    if (digits.length < 4) return null
    return "•••• " + digits.takeLast(4)
}
