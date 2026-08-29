package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Баланс кошелька — ровно столько, сколько нужно checkout'у (эпик 5.3).
 *
 * Полноценный кошелёк (пополнение, история, платежи) — эпик 8; когда он
 * появится, этот интерфейс переедет в его слой данных, а checkout не заметит
 * разницы.
 */
interface WalletRepository {

    suspend fun balance(): ApiResult<Long>
}

@Singleton
class DefaultWalletRepository @Inject constructor(
    private val api: FoodApi,
) : WalletRepository {

    override suspend fun balance(): ApiResult<Long> =
        apiCall { api.walletBalance() }.map { it.balance.coerceAtLeast(0) }
}
