package uz.mahalla.feature.wallet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Кошелёк (контроллер `wallet`, issue #62 и #93).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl): все ручки требуют Bearer —
 * без токена приходит `401 UNAUTHORIZED`, — поэтому API создаётся на
 * **основном** Retrofit, а не на «голом» `@RefreshClient`. Ответы приезжают в
 * общем конверте `{success, data, error}`.
 *
 * Гео-заголовки обязательны и здесь (`403 GEO_PERMISSION_REQUIRED` без них,
 * проверено), но их уже ставит `GeoHeaderInterceptor` на обоих клиентах
 * (issue #53).
 */
interface WalletApi {

    @GET("wallet")
    suspend fun wallet(): ApiResponse<WalletDto>

    @GET("wallet/transactions")
    suspend fun transactions(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<TransactionPageDto>

    @POST("wallet/top-up")
    suspend fun topUp(@Body request: TopUpRequest): ApiResponse<TopUpDto>
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

/**
 * `TopUpRequest`. Оба поля обязательны, значения по умолчанию не объявлены
 * намеренно: kotlinx.serialization выбрасывает из тела поля, равные дефолту, и
 * бэкенд получал бы запрос без суммы (та же грабля, что у `revokeAll` в
 * issue #61).
 *
 * `amount` — **в единицах бэкенда**, не в сумах: перевод делает
 * [uz.mahalla.feature.wallet.domain.WalletTopUp.toMinor] по делителю, который
 * вывела выдача баланса.
 */
@Serializable
data class TopUpRequest(
    @SerialName("amount") val amount: Long,
    @SerialName("provider") val provider: String,
)

/**
 * `TopUpResponse` — ответ на заведённый платёж. Все поля необязательные, как
 * везде в этом API; без `paymentUrl` платить негде, и это разбирает
 * репозиторий.
 */
@Serializable
data class TopUpDto(
    @SerialName("paymentUrl") val paymentUrl: String? = null,
    @SerialName("transactionId") val transactionId: String? = null,
    @SerialName("amount") val amount: Long? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
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
