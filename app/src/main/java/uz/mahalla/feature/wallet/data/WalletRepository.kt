package uz.mahalla.feature.wallet.data

import uz.mahalla.core.format.Money
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.tiyinToSom
import uz.mahalla.core.paging.hasMorePages
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.wallet.domain.PaymentLink
import uz.mahalla.feature.wallet.domain.TopUpDraft
import uz.mahalla.feature.wallet.domain.TopUpOrder
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.TopUpValidator
import uz.mahalla.feature.wallet.domain.TransactionDirection
import uz.mahalla.feature.wallet.domain.TransactionStatus
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Баланс и история операций (issue #62).
 *
 * Кэша нет намеренно: деньги показываются такими, какими их видит сервер, —
 * устаревший баланс из Room хуже честной ошибки. По той же причине пустой
 * ответ остаётся пустым списком, а не подменяется прошлым.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface WalletRepository {

    suspend fun wallet(): ApiResult<Wallet>

    suspend fun transactions(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<WalletTransactionPage>

    /**
     * Заводит пополнение и возвращает форму оплаты (issue #93).
     *
     * @param amountSum сумма в сумах — ровно та, что человек видел на экране.
     * В тийины бэкенда она переводится здесь, на границе данных
     * ([Money.somToTiyin], issue #149), а не на экране.
     */
    suspend fun topUp(
        amountSum: Long,
        provider: TopUpProvider,
    ): ApiResult<TopUpOrder>

    companion object {
        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20

        /** Код отказа, когда черновик не прошёл проверку ещё на клиенте. */
        const val INVALID_TOP_UP_CODE = "TOP_UP_INVALID"

        /** Код отказа, когда сервер не дал ссылки на форму оплаты. */
        const val NO_PAYMENT_URL_CODE = "TOP_UP_NO_PAYMENT_URL"
    }
}

@Singleton
class DefaultWalletRepository @Inject constructor(
    private val api: WalletApi,
) : WalletRepository {

    override suspend fun wallet(): ApiResult<Wallet> =
        apiCall { api.wallet().payload() }.map(WalletDto::toDomain)

    override suspend fun transactions(page: Int, size: Int): ApiResult<WalletTransactionPage> =
        apiCall { api.transactions(page = page.coerceAtLeast(0), size = size).payload() }
            .map(TransactionPageDto::toDomain)

    /**
     * Сумма ниже серверного минимума в сеть не уходит: 400 сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения (то же правило, что у отзыва в issue #76 и заявки продавца в
     * issue #84).
     *
     * Ответ без годной ссылки — отказ, а не успех: платить человеку негде, и
     * показать «платёж заведён» без формы значило бы соврать. Отдельный код
     * отказа вместо `ApiError.Serialization` — потому что ответ разобрался, в
     * нём просто нет того, ради чего запрос делался.
     */
    override suspend fun topUp(
        amountSum: Long,
        provider: TopUpProvider,
    ): ApiResult<TopUpOrder> {
        val draft = TopUpDraft(amountText = amountSum.toString(), provider = provider)
        if (TopUpValidator.validate(draft).isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(WalletRepository.INVALID_TOP_UP_CODE))
        }
        val result = apiCall {
            api.topUp(
                TopUpRequest(
                    amount = Money.somToTiyin(amountSum),
                    provider = provider.apiValue,
                ),
            ).payload()
        }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val url = PaymentLink.sanitize(result.data.paymentUrl)
                    ?: return ApiResult.Failure(
                        ApiError.Business(WalletRepository.NO_PAYMENT_URL_CODE),
                    )
                ApiResult.Success(TopUpOrder(paymentUrl = url))
            }
        }
    }
}

/**
 * Суммы приходят в тийинах, дробные близнецы `*Som` игнорируются: единицу
 * бэкенд задокументировал, выводить её из пары больше незачем (issue #149).
 *
 * Отрицательный баланс — ошибка сервера: показывать «−5 000» на карточке
 * кошелька незачем, а на вопрос «хватает ли денег» он влияет так же, как ноль.
 * Списания при этом знак сохраняют: там минус и есть смысл строки.
 */
internal fun WalletDto.toDomain(): Wallet {
    val balanceSum = (balance ?: 0).tiyinToSom().coerceAtLeast(0)
    val heldSum = (heldAmount ?: 0).tiyinToSom().coerceAtLeast(0)
    return Wallet(
        balanceSum = balanceSum,
        bonusSum = (bonusBalance ?: 0).tiyinToSom().coerceAtLeast(0),
        heldSum = heldSum,
        // Поля может не быть — тогда «доступно» считается тем же способом, что
        // и на сервере: заморозка вычитается из баланса.
        availableSum = availableBalance
            ?.tiyinToSom()
            ?.coerceAtLeast(0)
            ?: (balanceSum - heldSum).coerceAtLeast(0),
        currency = currency?.takeIf { it.isNotBlank() },
        status = WalletStatus.fromServer(status),
    )
}

/**
 * Разбор мягкий, как в каталоге (issue #53): операция без `id` отбрасывается —
 * в `LazyColumn` она стала бы дубликатом ключа, а отличить её от соседней всё
 * равно нечем.
 *
 * `hasMore` — общее правило [hasMorePages] (issue #142): `last`, иначе
 * `page`/`totalPages`, иначе останавливаемся.
 */
internal fun TransactionPageDto.toDomain(): WalletTransactionPage = WalletTransactionPage(
    items = content.mapNotNull(TransactionDto::toDomain),
    hasMore = hasMorePages(page = page, totalPages = totalPages, last = last),
)

internal fun TransactionDto.toDomain(): WalletTransaction? {
    val transactionId = id?.takeIf { it.isNotBlank() } ?: return null
    val signed = (amount ?: 0).tiyinToSom()
    val movement = TransactionDirection.fromServer(direction)
    return WalletTransaction(
        id = transactionId,
        type = type?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        direction = movement,
        amountSum = abs(signed),
        // Направление сильнее знака суммы: сервер шлёт списания и с минусом, и
        // без него, а строка обязана читаться одинаково.
        signedAmountSum = when (movement) {
            TransactionDirection.In -> abs(signed)
            TransactionDirection.Out -> -abs(signed)
            TransactionDirection.Unknown -> signed
        },
        isBonus = isBonus,
        balanceAfterSum = balanceAfter?.tiyinToSom(),
        status = TransactionStatus.fromServer(status),
        createdAt = parseServerInstant(createdAt),
    )
}
