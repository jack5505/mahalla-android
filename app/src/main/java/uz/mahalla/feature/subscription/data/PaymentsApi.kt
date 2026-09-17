package uz.mahalla.feature.subscription.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Платежи (контроллер `payment`, эпик #13, задача 9.3) — ровно одна ручка:
 * история платежей, из которой экран подписки берёт **списания за подписку**.
 *
 * Контракт снят со схемы стенда (`/v3/api-docs`, 2026-09-08). Живым запросом
 * не проверен: ручка требует Bearer, а SMS-кода в CI нет (то же ограничение,
 * что у всего контроллера подписок в issue #103).
 *
 * Что важно знать про эту ручку:
 *
 * - **фильтра по назначению у неё нет** — приезжают все платежи человека, и
 *   подписочные отбираются на клиенте по `purpose`
 *   ([uz.mahalla.feature.subscription.domain.SubscriptionCharge.isSubscriptionPurpose]);
 * - **отдаёт сырую сущность** `PaymentTransaction`, а не отдельный
 *   `*Response`: отсюда и `createdBy`/`updatedBy` в схеме, которые приложению
 *   не нужны. `amount` — тийины, как и все целые денежные поля бэкенда
 *   (issue #149, [uz.mahalla.core.format.Money]);
 * - `purposeId` (id подписки) в домен не берётся: показывать его человеку
 *   нечем, а перехода к сущности бэкенд не даёт.
 *
 * Остальные ручки контроллера не используются: `payments/subscription` отдаёт
 * строго меньше, чем `subscriptions/current` (см. `SubscriptionsApi`),
 * `payments/subscription/activate` принимает `Map<String,String>` с
 * неизвестными полями, а callbacks Click/Payme вызывает не приложение.
 */
interface PaymentsApi {

    /** История платежей. Пагинация настоящая: `page`/`size`, у сервера дефолт `0`/`20`. */
    @GET("payments/transactions")
    suspend fun transactions(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<PaymentTransactionPageDto>
}

/** `PageResponsePaymentTransaction`. */
@Serializable
data class PaymentTransactionPageDto(
    @SerialName("content") val content: List<PaymentTransactionDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `PaymentTransaction`. Все поля необязательные, как везде в этом API:
 * отсутствие любого из них — не повод показать экран ошибки вместо истории.
 */
@Serializable
data class PaymentTransactionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("amount") val amount: Long? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("purpose") val purpose: String? = null,
    @SerialName("errorMessage") val errorMessage: String? = null,
)
