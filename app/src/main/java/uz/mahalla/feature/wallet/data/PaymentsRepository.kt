package uz.mahalla.feature.wallet.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.tiyinToSom
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.subscription.data.PaymentTransactionDto
import uz.mahalla.feature.subscription.data.PaymentTransactionPageDto
import uz.mahalla.feature.subscription.data.PaymentsApi
import uz.mahalla.feature.subscription.data.hasMore
import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import uz.mahalla.feature.wallet.domain.PaymentTransaction
import uz.mahalla.feature.wallet.domain.PaymentTransactionPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * История платежей PAYME/CLICK/UZUM в кошельке (issue #184).
 *
 * Своей ручки у вкладки «Платежи» нет: используется тот же `PaymentsApi`
 * (`GET payments/transactions`), что и у списаний за подписку
 * ([uz.mahalla.feature.subscription.data.SubscriptionRepository.charges]), но
 * без фильтра по назначению — здесь должны быть видны все платежи, а не
 * только подписочные.
 *
 * Кэша нет по той же причине, что и у [WalletRepository]: устаревший статус
 * платежа хуже честного «не удалось загрузить».
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface PaymentsRepository {

    suspend fun transactions(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<PaymentTransactionPage>

    companion object {
        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultPaymentsRepository @Inject constructor(
    private val api: PaymentsApi,
) : PaymentsRepository {

    override suspend fun transactions(page: Int, size: Int): ApiResult<PaymentTransactionPage> {
        val requestedPage = page.coerceAtLeast(0)
        return apiCall { api.transactions(page = requestedPage, size = size).payload() }
            .map { it.toWalletDomain(requestedPage) }
    }
}

private fun PaymentTransactionPageDto.toWalletDomain(requestedPage: Int): PaymentTransactionPage =
    PaymentTransactionPage(
        items = content.mapNotNull(PaymentTransactionDto::toWalletDomain),
        hasMore = hasMore(requestedPage),
    )

/**
 * Платёж без `id` отбрасывается — в `LazyColumn` он дубликат ключа, а
 * отличить его от соседнего всё равно нечем (то же правило, что у операции
 * кошелька и у списания за подписку).
 *
 * `amount` — тийины, как все целые денежные поля бэкенда (issue #149).
 */
private fun PaymentTransactionDto.toWalletDomain(): PaymentTransaction? {
    val paymentId = id?.takeIf { it.isNotBlank() } ?: return null
    return PaymentTransaction(
        id = paymentId,
        provider = ChargeProvider.fromServer(provider),
        // Отрицательный платёж — ошибка сервера: «−49 000» в истории платежей
        // не значит ничего.
        amountSum = (amount ?: 0).tiyinToSom().coerceAtLeast(0),
        status = ChargeStatus.fromServer(status),
        purpose = purpose?.takeIf { it.isNotBlank() },
        errorMessage = errorMessage?.takeIf { it.isNotBlank() },
        createdAt = parseServerInstant(createdAt),
    )
}
