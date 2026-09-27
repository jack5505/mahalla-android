package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.wallet.data.PaymentsRepository
import uz.mahalla.feature.wallet.domain.PaymentTransactionPage

/**
 * Платежи в памяти (issue #184): экран кошелька проверяется без MockWebServer.
 * Ответ на каждую страницу задаётся отдельно — иначе догрузку не отличить от
 * повторной загрузки первой страницы, как и у [FakeWalletRepository].
 */
class FakePaymentsRepository : PaymentsRepository {

    var pages: MutableMap<Int, ApiResult<PaymentTransactionPage>> = mutableMapOf()

    var defaultPage: ApiResult<PaymentTransactionPage> =
        ApiResult.Success(PaymentTransactionPage())

    val requestedPages = mutableListOf<Int>()

    override suspend fun transactions(page: Int, size: Int): ApiResult<PaymentTransactionPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }
}
