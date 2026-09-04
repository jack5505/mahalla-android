package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.feature.wallet.domain.TopUpOrder
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletTransactionPage

/**
 * Кошелёк в памяти: ViewModel'и кошелька и checkout'а проверяются без
 * MockWebServer. Ответ на каждую страницу истории задаётся отдельно — иначе
 * догрузку не отличить от повторной загрузки первой страницы.
 */
class FakeWalletRepository(
    initial: Wallet = Wallet(balanceSum = 1_000_000, availableSum = 1_000_000),
) : WalletRepository {

    var wallet: ApiResult<Wallet> = ApiResult.Success(initial)

    var pages: MutableMap<Int, ApiResult<WalletTransactionPage>> = mutableMapOf()

    var defaultPage: ApiResult<WalletTransactionPage> =
        ApiResult.Success(WalletTransactionPage())

    var walletCount: Int = 0
        private set
    val requestedPages = mutableListOf<Int>()

    override suspend fun wallet(): ApiResult<Wallet> {
        walletCount++
        return wallet
    }

    override suspend fun transactions(page: Int, size: Int): ApiResult<WalletTransactionPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    /** Что вернуть на попытку пополнения (issue #93). */
    var topUp: ApiResult<TopUpOrder> = ApiResult.Success(
        TopUpOrder(paymentUrl = "https://checkout.paycom.uz/abc"),
    )

    /** Запросы пополнения: сумма уходит в сумах, перевод в единицы — внутри. */
    val topUpRequests = mutableListOf<Triple<Long, TopUpProvider, Long>>()

    override suspend fun topUp(
        amountSum: Long,
        provider: TopUpProvider,
        scale: Long,
    ): ApiResult<TopUpOrder> {
        topUpRequests += Triple(amountSum, provider, scale)
        return topUp
    }
}
