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

    /**
     * Кошелёк заведения (issue #290, бэкенд jack5505/mahalla#223).
     *
     * Путь и тело сняты с живого `/v3/api-docs` **2026-09-22** (стенд отдал
     * схему анонимно, без `CONTRACT_REFRESH_TOKEN`) — `ApiResponseWalletResponse`,
     * тот же `WalletResponse`, что и у [wallet]: своей DTO под бизнес-кошелёк
     * заводить незачем, схема слово в слово совпадает.
     */
    @GET("wallet/business")
    suspend fun businessWallet(): ApiResponse<WalletDto>

    /**
     * Заявка на вывод (issue #290, бэкенд jack5505/mahalla#223). Схема — оттуда
     * же, что и [businessWallet].
     *
     * ⚠️ Отдельной ручки для списка заявок и их статусов у бэкенда нет: в схеме
     * — только эта, `POST`. Статус заявки виден только в её собственном ответе
     * и, если появится там, в [transactions] (там же — разведённые начисление
     * и комиссия, и разворот при возврате: своя схема их не выделяет, это
     * обычные записи истории). Список заявок целиком — NEEDS-PARTNER.
     */
    @POST("wallet/business/payouts")
    suspend fun requestPayout(@Body request: PayoutCreateRequest): ApiResponse<PayoutDto>
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
    /**
     * Тот же баланс «в сумах» — для удобства чтения ответа. Раньше по этой паре
     * выводилась единица целых полей; теперь она известна — тийины (issue #149).
     */
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
 * `amount` — **в тийинах**, не в сумах: перевод делает
 * [uz.mahalla.core.format.Money.somToTiyin] в репозитории (issue #149).
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

/**
 * `PayoutCreateRequest`. Оба поля обязательны: `amount` — тийины, как и везде
 * в кошельке ([uz.mahalla.core.format.Money.somToTiyin]), `cardNumber` — по
 * схеме ровно 16 цифр (`\d{16}`), без пробелов и тире.
 */
@Serializable
data class PayoutCreateRequest(
    @SerialName("amount") val amount: Long,
    @SerialName("cardNumber") val cardNumber: String,
)

/**
 * `PayoutResponse` — ответ на заведённую заявку на вывод. Все поля
 * необязательные, как и везде в этом API; полный номер карты сервер
 * присылает обратно, но на экране он не показывается — только маска
 * (issue #290).
 */
@Serializable
data class PayoutDto(
    @SerialName("id") val id: String? = null,
    @SerialName("amount") val amount: Long? = null,
    @SerialName("amountSom") val amountSom: Double? = null,
    @SerialName("cardNumber") val cardNumber: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
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
