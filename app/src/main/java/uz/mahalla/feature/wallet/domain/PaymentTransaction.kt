package uz.mahalla.feature.wallet.domain

import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import java.time.Instant

/**
 * Платёж PAYME/CLICK/UZUM (`PaymentTransaction`, `GET payments/transactions`,
 * issue #184).
 *
 * В отличие от [WalletTransaction] это не движение по счёту, а сам платёж
 * провайдеру: со статусом отказа и его причиной ([errorMessage]), которых у
 * движения по счёту нет. Тот же ответ уже читает подписка
 * ([uz.mahalla.feature.subscription.data.SubscriptionRepository.charges]) —
 * оттуда и переиспользованы [ChargeStatus]/[ChargeProvider], своей ручки у
 * вкладки «Платежи» нет.
 *
 * @param amountSum сумма — в сумах, перевод из тийинов сделан на границе
 * данных (issue #149).
 * @param errorMessage причина отказа как есть — свой текст приложение
 * придумать не может, а «платёж не прошёл» без причины — вопрос в поддержку.
 */
data class PaymentTransaction(
    val id: String,
    val provider: ChargeProvider = ChargeProvider.Unknown,
    val amountSum: Long = 0,
    val status: ChargeStatus = ChargeStatus.Unknown,
    val purpose: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant? = null,
)

/** Страница истории платежей — пагинация настоящая, как у [WalletTransactionPage]. */
data class PaymentTransactionPage(
    val items: List<PaymentTransaction> = emptyList(),
    val hasMore: Boolean = false,
)
