package uz.mahalla.feature.wallet.domain

/**
 * Пополнение кошелька (issue #93, задача 8.2 эпика #12).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl):
 * `POST wallet/top-up` с телом `TopUpRequest{amount, provider}` — оба поля
 * обязательны, `amount` целое с минимумом `100000` **в единицах бэкенда**,
 * `provider` по шаблону `PAYME|CLICK|UZUM`. В ответе `TopUpResponse`
 * `{paymentUrl, transactionId, amount, provider, expiresAt}`, то есть платит
 * человек в веб-форме провайдера, а приложение только заводит платёж.
 */
enum class TopUpProvider(val apiValue: String) {
    Payme("PAYME"),
    Click("CLICK"),
    Uzum("UZUM"),
    ;

    companion object {

        /**
         * Провайдер из ответа сервера или `null`.
         *
         * Незнакомое значение не подменяется первым в списке: назвать чужой
         * платёж «Payme» хуже, чем не назвать его никак.
         */
        fun fromServer(value: String?): TopUpProvider? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null
            return entries.firstOrNull { it.apiValue.equals(raw, ignoreCase = true) }
        }
    }
}

/**
 * Суммы пополнения.
 *
 * Единицу денег бэкенда приложение не зашивает, а **выводит из ответа**
 * (issue #62, [WalletAmounts]): у баланса есть дробный близнец `balanceSom`, и
 * делитель выбирается по этой паре. Здесь тот же делитель работает в обратную
 * сторону — из сумов, которые ввёл человек, в единицы бэкенда.
 *
 * Поэтому и минимум переводится в сумы тем же делителем: серверные `100000` —
 * это 1 000 сум при тийинах и 100 000 сум при сумах, и подпись под полем
 * обязана называть то число, которое поле примет.
 */
object WalletTopUp {

    /** `TopUpRequest.amount.minimum` — в единицах бэкенда. */
    const val MIN_AMOUNT_MINOR = 100_000L

    /**
     * Потолок на клиенте. У бэкенда его нет, и он здесь не про его правила, а
     * про лишний ноль: пополнение на сто миллионов сум — почти наверняка
     * опечатка, а платит человек настоящими деньгами в форме провайдера, где
     * отменять уже поздно.
     */
    const val MAX_AMOUNT_SUM = 100_000_000L

    /** Минимальная сумма пополнения в сумах при данном делителе. */
    fun minAmountSum(scale: Long): Long {
        if (scale <= 1L) return MIN_AMOUNT_MINOR
        // Вверх: при округлении вниз подпись обещала бы сумму, которую сервер
        // отвергнет как слишком маленькую.
        return (MIN_AMOUNT_MINOR + scale - 1) / scale
    }

    /** Сумма в единицах бэкенда. */
    fun toMinor(amountSum: Long, scale: Long): Long =
        if (scale <= 1L) amountSum else amountSum * scale

    /**
     * Сумма из того, что набрано в поле.
     *
     * Цифры выбираются из строки: `MoneyFormatter` показывает суммы с
     * неразрывными пробелами между разрядами, и вставленное из другого места
     * «100 000» — обычный ввод, а не мусор. Всё остальное (буквы, минус,
     * дробная часть) суммой не становится: сум — целая единица, а тийинами
     * кошелёк не пополняют.
     */
    fun parseAmount(raw: String): Long? {
        val digits = raw.filter(Char::isDigit)
        if (digits.isEmpty() || digits.length > MAX_DIGITS) return null
        return digits.toLongOrNull()?.takeIf { it > 0 }
    }

    /** Столько цифр не наберётся ни в одной осмысленной сумме. */
    private const val MAX_DIGITS = 12
}

/**
 * Черновик пополнения. Провайдер обязателен и по умолчанию не выбран: у трёх
 * платёжных систем разные комиссии и разные приложения, и выбрать за человека
 * ту, которой он не пользуется, — заставить его вернуться с полпути.
 */
data class TopUpDraft(
    val amountText: String = "",
    val provider: TopUpProvider? = null,
) {
    val amountSum: Long? get() = WalletTopUp.parseAmount(amountText)
}

/** Почему черновик нельзя отправить. Показывается по одной причине на поле. */
enum class TopUpError {
    AmountRequired,
    AmountTooSmall,
    AmountTooLarge,
    ProviderRequired,
}

object TopUpValidator {

    /**
     * Все ошибки сразу, как в остальных формах приложения (issue #84): форма
     * короткая, но замечания по одному гоняли бы человека между полем и
     * списком провайдеров.
     *
     * @param scale делитель из ответа кошелька — от него зависит минимум.
     */
    fun validate(draft: TopUpDraft, scale: Long): Set<TopUpError> {
        val errors = mutableSetOf<TopUpError>()
        val amount = draft.amountSum
        when {
            amount == null -> errors += TopUpError.AmountRequired
            amount < WalletTopUp.minAmountSum(scale) -> errors += TopUpError.AmountTooSmall
            amount > WalletTopUp.MAX_AMOUNT_SUM -> errors += TopUpError.AmountTooLarge
        }
        if (draft.provider == null) errors += TopUpError.ProviderRequired
        return errors
    }
}

/**
 * Заведённый платёж — то есть адрес формы, куда человек идёт платить.
 *
 * Остальное из `TopUpResponse` (`transactionId`, `amount`, `provider`,
 * `expiresAt`) в домен не доезжает намеренно: суммой и провайдером экран уже
 * располагает — их только что подтвердил человек, — а сверять исход платежа по
 * `GET payments/transactions` нечем: что там `id`, а что `externalOrderId`, из
 * схемы не следует. В DTO поля объявлены и документируют контракт (как
 * `imageUrl` уведомления в issue #81).
 */
data class TopUpOrder(val paymentUrl: String)

/**
 * Ссылка на форму оплаты.
 *
 * Ссылку присылает сервер, а адрес сервера в debug вводит пользователь
 * (issue #26) — без проверки подменённый бэкенд запускал бы на устройстве
 * произвольный intent (`intent://`, чужой deep link, в том числе наш
 * `mahalla://`). То же правило, что у ссылки на бота (issue #46), на магазин
 * (issue #80) и на сайт заведения (issue #84).
 *
 * Здесь оно строже: **только `https`**. Форма оплаты по `http` — это ввод
 * карты в канал, где его подменят, а `market:`/`tg:` формой оплаты не бывают.
 * Хосты не ограничены: провайдеров три, у каждого свои домены и промежуточные
 * шлюзы, и белый список ломался бы при первой же их правке.
 *
 * Разбор ручной, без `android.net.Uri`: правило проверяется JVM-тестом, а
 * `Uri` в юнит-тестах заглушен и молча вернул бы `null` у каждого поля — тест
 * был бы зелёным при любой реализации.
 */
object PaymentLink {

    private const val HTTPS = "https://"

    fun sanitize(url: String?): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        if (!candidate.startsWith(HTTPS, ignoreCase = true)) return null
        val rest = candidate.substring(HTTPS.length)
        // Хост обязан быть: `https://` и `https:///path` открывать некуда, а
        // пробел внутри — признак того, что сервер склеил ссылку с текстом.
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || candidate.any(Char::isWhitespace)) return null
        return candidate
    }
}
