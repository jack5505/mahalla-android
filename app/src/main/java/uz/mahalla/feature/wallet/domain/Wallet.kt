package uz.mahalla.feature.wallet.domain

import java.time.Instant

/**
 * Кошелёк пользователя (`WalletResponse`, issue #62).
 *
 * Все суммы — целые сумы, как и везде в приложении ([uz.mahalla.core.format.MoneyFormatter]):
 * пересчёт из младших единиц бэкенда делает [WalletAmounts] на границе данных,
 * дальше по коду единица одна.
 *
 * @param balanceSum основной баланс.
 * @param bonusSum бонусы — отдельный кошелёк бэкенда, их нельзя вывести.
 * @param heldSum заморожено под незавершённые операции. Показывается отдельной
 * строкой: разница между «на счету» и «можно потратить» — самый частый вопрос
 * к кошельку.
 * @param availableSum сколько можно потратить прямо сейчас. Именно это число
 * checkout сравнивает с суммой заказа.
 * @param amountScale делитель, которым суммы этого ответа переведены в сумы
 * ([WalletAmounts.scaleOf]). Он остаётся в домене ради пополнения (issue #93):
 * `amount` в `POST wallet/top-up` уходит в единицах бэкенда, и переводить
 * сумы обратно надо тем же делителем, который вывела эта же выдача, — иначе
 * экран показывал бы одну единицу, а платёж уходил в другой.
 */
data class Wallet(
    val balanceSum: Long = 0,
    val bonusSum: Long = 0,
    val heldSum: Long = 0,
    val availableSum: Long = 0,
    val currency: String? = null,
    val status: WalletStatus = WalletStatus.Unknown,
    val amountScale: Long = WalletAmounts.TIYIN_IN_SOM,
)

/**
 * Состояние счёта. Незнакомое значение — [Unknown], а не «заблокирован»: новый
 * статус бэкенда не должен превращаться в пугающую плашку у всех подряд.
 */
enum class WalletStatus {
    Active,
    Blocked,
    Unknown,
    ;

    companion object {

        private val BLOCKED = setOf("BLOCKED", "FROZEN", "SUSPENDED", "CLOSED")

        fun fromServer(value: String?): WalletStatus = when (normalizedServerValue(value)) {
            "ACTIVE" -> Active
            in BLOCKED -> Blocked
            else -> Unknown
        }
    }
}

/**
 * Операция по кошельку (`TransactionResponse`).
 *
 * @param amountSum величина операции без знака.
 * @param signedAmountSum та же величина со знаком направления — это и есть то,
 * что видит человек в списке.
 * @param balanceAfterSum остаток после операции; `null`, если сервер его не
 * прислал (у бонусных начислений поля может не быть).
 */
data class WalletTransaction(
    val id: String,
    val type: String? = null,
    val description: String? = null,
    val direction: TransactionDirection = TransactionDirection.Unknown,
    val amountSum: Long = 0,
    val signedAmountSum: Long = 0,
    val isBonus: Boolean = false,
    val balanceAfterSum: Long? = null,
    val status: TransactionStatus = TransactionStatus.Unknown,
    val createdAt: Instant? = null,
)

/**
 * Направление операции. Написание бэкенд не фиксирует (в схеме это `string`),
 * поэтому принимаются все распространённые пары; незнакомое значение —
 * [Unknown], и тогда знак берётся из самой суммы: списание, показанное как
 * пополнение, хуже, чем операция без знака.
 */
enum class TransactionDirection {
    In,
    Out,
    Unknown,
    ;

    companion object {

        private val INCOMING = setOf("IN", "INCOMING", "CREDIT", "DEPOSIT", "INCOME", "TOP_UP")
        private val OUTGOING = setOf("OUT", "OUTGOING", "DEBIT", "WITHDRAWAL", "EXPENSE", "PAYMENT")

        fun fromServer(value: String?): TransactionDirection =
            when (normalizedServerValue(value)) {
                in INCOMING -> In
                in OUTGOING -> Out
                else -> Unknown
            }
    }
}

/**
 * Исход операции. `PENDING` и `FAILED` показываются бейджем: деньги, ушедшие
 * «в никуда», человек обязан отличать от прошедшего платежа.
 */
enum class TransactionStatus {
    Pending,
    Completed,
    Failed,
    Unknown,
    ;

    companion object {

        private val PENDING = setOf("PENDING", "PROCESSING", "IN_PROGRESS", "CREATED", "HOLD")
        private val COMPLETED = setOf("COMPLETED", "SUCCESS", "SUCCEEDED", "DONE", "PAID")
        private val FAILED = setOf("FAILED", "CANCELLED", "CANCELED", "REJECTED", "REVERSED", "ERROR")

        fun fromServer(value: String?): TransactionStatus =
            when (normalizedServerValue(value)) {
                in PENDING -> Pending
                in COMPLETED -> Completed
                in FAILED -> Failed
                else -> Unknown
            }
    }
}

/**
 * Значение перечисления бэкенда в сравнимом виде: регистр и пробелы приходят
 * от сервера как придётся, а разбор не должен от них зависеть.
 */
private fun normalizedServerValue(value: String?): String = value?.trim()?.uppercase().orEmpty()

/**
 * Страница истории. Пагинация у бэкенда настоящая (`page`/`size`,
 * `totalPages`, `last`), в отличие от каталога (issue #53).
 */
data class WalletTransactionPage(
    val items: List<WalletTransaction> = emptyList(),
    val hasMore: Boolean = false,
)
