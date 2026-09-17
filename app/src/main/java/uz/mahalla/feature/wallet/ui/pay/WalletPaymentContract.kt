package uz.mahalla.feature.wallet.ui.pay

import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.feature.wallet.domain.WalletPaymentConfirmation
import uz.mahalla.feature.wallet.domain.WalletPaymentRejection

/**
 * Ход оплаты из кошелька (задача 8.3 эпика #12) — то, что видит человек в
 * шторке подтверждения.
 *
 * Состояние одно на все вертикали: подтверждение заказа еды, одежды и билета
 * различается только суммой, поэтому и экран у них общий
 * ([PaymentConfirmSheet]).
 *
 * @param amountSum сумма к списанию в сумах — ровно та, которую человек видел
 * в итоге заказа.
 * @param availableSum сколько на кошельке доступно; `null` — баланс получить
 * не удалось. Это не отказ: решающее слово всё равно за сервером, и запрещать
 * оплату из-за неотвеченного запроса хуже, чем получить отказ от него
 * (то же правило, что в checkout'е еды).
 * @param method чем подтверждаем; `null` — подтверждать нечем, шаг пропущен.
 * @param attemptsLeft сколько попыток PIN осталось.
 * @param rejection почему оплата не состоялась.
 */
data class WalletPaymentState(
    val amountSum: Long = 0,
    val availableSum: Long? = null,
    val step: WalletPaymentStep = WalletPaymentStep.Preparing,
    val method: PaymentConfirmationMethod? = null,
    val pin: OtpFieldState = OtpFieldState(),
    val attemptsLeft: Int = WalletPaymentConfirmation.MAX_PIN_ATTEMPTS,
    val rejection: WalletPaymentRejection? = null,
) {

    /** Идёт запрос, проверка PIN или загрузка баланса — трогать форму нельзя. */
    val isBusy: Boolean
        get() = step != WalletPaymentStep.Confirm && step != WalletPaymentStep.Rejected

    /** Ввод PIN: только когда его и ждём. */
    val isPinStep: Boolean
        get() = step == WalletPaymentStep.Confirm && method == PaymentConfirmationMethod.Pin

    /**
     * Отказ можно повторить тем же ключом идемпотентности. Недостаток средств
     * повтором не лечится — там кнопка ведёт в пополнение.
     */
    val canRetry: Boolean
        get() = (rejection as? WalletPaymentRejection.Declined)?.canRetry == true

    /** Сколько добрать на кошелёк, если сумму посчитал клиент. */
    val missingSum: Long?
        get() = (rejection as? WalletPaymentRejection.InsufficientFunds)?.missingSum
}

/** Шаг оплаты. */
enum class WalletPaymentStep {
    /** Перечитываем баланс и выясняем, чем подтверждать. */
    Preparing,

    /** Ждём PIN или системный промпт. */
    Confirm,

    /** Проверяем набранный PIN — локально, но не мгновенно (PBKDF2). */
    Checking,

    /** Запрос ушёл — второго в этот момент быть не должно. */
    Submitting,

    /** Оплата не состоялась, причина — в [WalletPaymentState.rejection]. */
    Rejected,
}
