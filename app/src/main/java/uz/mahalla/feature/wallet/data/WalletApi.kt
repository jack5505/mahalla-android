package uz.mahalla.feature.wallet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Кошелёк (контроллер `wallet`, issue #62).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl): обе ручки требуют Bearer —
 * без токена приходит `401 UNAUTHORIZED`, — поэтому API создаётся на
 * **основном** Retrofit, а не на «голом» `@RefreshClient`. Ответы приезжают в
 * общем конверте `{success, data, error}`.
 *
 * Пополнения (`POST wallet/top-up`) здесь нет намеренно: оно требует выбора
 * платёжного провайдера (`PAYME|CLICK|UZUM`) и возврата из его веб-формы —
 * это задача 8.2 эпика #12, а не строка на экране баланса.
 */
interface WalletApi {

    @GET("wallet")
    suspend fun wallet(): ApiResponse<WalletDto>

    @GET("wallet/transactions")
    suspend fun transactions(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<TransactionPageDto>
}

/**
 * `WalletResponse`. Все поля необязательные: отсутствие любого из них — не
 * повод показать экран ошибки вместо баланса.
 */
@Serializable
data class WalletDto(
    @SerialName("id") val id: String? = null,
    @SerialName("balance") val balance: Long? = null,
    @SerialName("bonusBalance") val bonusBalance: Long? = null,
    @SerialName("heldAmount") val heldAmount: Long? = null,
    @SerialName("availableBalance") val availableBalance: Long? = null,
    @SerialName("totalAvailable") val totalAvailable: Long? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("status") val status: String? = null,
    /** Тот же баланс «в сумах» — по нему определяется единица целых полей. */
    @SerialName("balanceSom") val balanceSom: Double? = null,
    @SerialName("bonusBalanceSom") val bonusBalanceSom: Double? = null,
)

/** `PageResponseTransactionResponse` — пагинация у кошелька настоящая. */
@Serializable
data class TransactionPageDto(
    @SerialName("content") val content: List<TransactionDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/** `TransactionResponse`. */
@Serializable
data class TransactionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("direction") val direction: String? = null,
    @SerialName("amount") val amount: Long? = null,
    @SerialName("amountSom") val amountSom: Double? = null,
    @SerialName("isBonus") val isBonus: Boolean = false,
    @SerialName("balanceAfter") val balanceAfter: Long? = null,
    @SerialName("referenceType") val referenceType: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)
