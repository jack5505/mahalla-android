package uz.mahalla.feature.wallet.domain

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import java.util.UUID

/**
 * Оплата из кошелька (задача 8.3 эпика #12).
 *
 * Отдельной ручки «списать с кошелька» у бэкенда нет: кошелёк — это
 * `paymentMethod = WALLET` внутри запроса вертикали (`POST food/orders`,
 * `POST fashion/orders`, `POST cinema/sessions/{id}/buy`, …), деньги списывает
 * сам сервер при создании заказа. Поэтому на клиенте 8.3 — это то, что
 * происходит **вокруг** этого запроса и одинаково для всех вертикалей:
 * проверка баланса, подтверждение (PIN/биометрия), один запрос на одно
 * подтверждение и разбор отказа.
 *
 * Логика здесь — чистые функции: их проверяет JVM-тест без ViewModel и без
 * сети, а сам ход оплаты — [uz.mahalla.feature.wallet.ui.pay.WalletPaymentFlow].
 */

/** Почему оплата не состоялась. */
sealed interface WalletPaymentRejection {

    /**
     * Денег не хватает.
     *
     * @param missingSum сколько добрать; `null` — сумму назвал сервер, а не
     * клиент, и разницы в ответе нет. Ноль вместо `null` был бы неправдой
     * («не хватает 0 сум»).
     */
    data class InsufficientFunds(val missingSum: Long?) : WalletPaymentRejection

    /** Кошелёк заблокирован — платежей он не примет, пополнение не поможет. */
    data object WalletBlocked : WalletPaymentRejection

    /**
     * Подтвердить не удалось: PIN исчерпал попытки, а биометрия недоступна или
     * отклонена. Запрос при этом не уходил — списывать нечего.
     */
    data object ConfirmationFailed : WalletPaymentRejection

    /**
     * Отказ сервера: стоп-лист позиции, отказ платёжного шлюза, 5xx, нет сети.
     * Отказ хранится целиком — текст бэкенда точнее нашего (issue #34), а
     * повторить попытку можно тем же ключом идемпотентности.
     */
    data class Declined(val failure: ApiFailure) : WalletPaymentRejection {

        /**
         * Стоит ли предлагать «повторить». Не предлагаем там, где повтор
         * заведомо повторит отказ: невалидный токен и запрет доступа сами не
         * пройдут, а кнопка без последствий читается как сломанная.
         */
        val canRetry: Boolean
            get() = when (failure.error) {
                ApiError.Unauthorized, ApiError.Forbidden, ApiError.NotFound -> false
                else -> true
            }
    }
}

/**
 * Что можно решить про оплату до запроса и по его ответу.
 *
 * Проверка до запроса нужна не вместо серверной, а чтобы не тратить чужие
 * деньги на дорогу: сервер всё равно проверит баланс сам, но человек к тому
 * моменту уже введёт PIN и подождёт ответ — ради «не хватает 12 000 сум»,
 * которые видно было сразу.
 */
object WalletPaymentGuard {

    /**
     * Машинные коды отказа, которые бэкенд возвращает при нехватке денег.
     *
     * Набор написан **по догадке**: `docs/API-CONTRACT.md` кодов кошелька не
     * фиксирует, стенд на 2026-09-08 отвечает `502`. Поэтому незнакомый код —
     * [WalletPaymentRejection.Declined] с текстом сервера, а не «пополните
     * кошелёк»: соврать про причину хуже, чем показать чужую формулировку.
     */
    private val INSUFFICIENT_FUNDS_CODES = setOf(
        "INSUFFICIENT_FUNDS",
        "INSUFFICIENT_BALANCE",
        "WALLET_INSUFFICIENT_FUNDS",
        "NOT_ENOUGH_FUNDS",
        "NOT_ENOUGH_BALANCE",
    )

    /** Коды заблокированного счёта — тоже по догадке, см. выше. */
    private val WALLET_BLOCKED_CODES = setOf(
        "WALLET_BLOCKED",
        "WALLET_FROZEN",
        "WALLET_SUSPENDED",
        "WALLET_INACTIVE",
    )

    /**
     * Отказ, который виден ещё до запроса; `null` — препятствий нет.
     *
     * Сравнивается именно «доступно» ([Wallet.availableSum]): заморозка под
     * другую незавершённую операцию потратить себя не даст (issue #62).
     */
    fun rejection(wallet: Wallet, amountSum: Long): WalletPaymentRejection? {
        if (wallet.status == WalletStatus.Blocked) return WalletPaymentRejection.WalletBlocked
        val missing = amountSum - wallet.availableSum
        if (missing > 0) return WalletPaymentRejection.InsufficientFunds(missing)
        return null
    }

    /** Отказ по ответу сервера. */
    fun rejection(failure: ApiFailure): WalletPaymentRejection {
        val code = (failure.error as? ApiError.Business)?.code
            ?: failure.server?.code
        return when (code?.trim()?.uppercase()) {
            in INSUFFICIENT_FUNDS_CODES -> WalletPaymentRejection.InsufficientFunds(null)
            in WALLET_BLOCKED_CODES -> WalletPaymentRejection.WalletBlocked
            else -> WalletPaymentRejection.Declined(failure)
        }
    }
}

/** Правила подтверждения оплаты. */
object WalletPaymentConfirmation {

    /**
     * Сколько раз можно ошибиться в PIN.
     *
     * Три — как на банковской карте: подтверждение локальное, и без предела
     * оставленный без присмотра телефон перебирался бы шестизначным кодом
     * до конца. Исчерпал — оплата отменяется целиком
     * ([WalletPaymentRejection.ConfirmationFailed]), заказ при этом на месте.
     */
    const val MAX_PIN_ATTEMPTS = 3
}

/**
 * Ключ идемпотентности платежа.
 *
 * Ключ создаётся один раз на одну оплату и переживает повторы: «повторить»
 * после таймаута отправляет **тот же** ключ, потому что сеть могла оборваться
 * уже после того, как сервер создал заказ и списал деньги.
 *
 * Настоящую защиту от двойного списания сейчас даёт клиент — flow не отправляет
 * второй запрос, пока не ответил первый, и не отправляет ничего после успеха.
 * Заголовок `Idempotency-Key` уходит вместе с запросом, но **поддержка на
 * стороне бэкенда не подтверждена** (`docs/API-CONTRACT.md`, issue про
 * контракт кошелька): сервер, который его игнорирует, ведёт себя как раньше.
 */
object IdempotencyKey {

    /** Имя заголовка запроса. */
    const val HEADER = "Idempotency-Key"

    fun random(): String = UUID.randomUUID().toString()
}
