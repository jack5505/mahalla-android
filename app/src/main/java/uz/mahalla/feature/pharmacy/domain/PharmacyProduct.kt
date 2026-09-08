package uz.mahalla.feature.pharmacy.domain

import androidx.compose.runtime.Immutable

/**
 * Наличие товара — то, ради чего аптеку и открывают.
 *
 * Три состояния, а не два: бэкенд отдаёт и флаг `isAvailable`, и остаток
 * `stockQuantity`, причём **оба необязательны**. Молчание сервера про наличие
 * — это «неизвестно», а не «есть»: человек поедет через полгорода за
 * лекарством, которого нет, и «в наличии» без основания дороже honest'ого «не
 * знаем».
 */
enum class ProductStock {
    /** Есть: либо флаг сказал «да», либо остаток положительный. */
    InStock,

    /** Нет: флаг сказал «нет» либо остаток нулевой. */
    OutOfStock,

    /** Сервер не сказал ни того, ни другого. */
    Unknown,
    ;

    companion object {
        /**
         * Отрицательный ответ сильнее положительного: если флаг говорит «нет»,
         * а остаток при этом положительный (рассинхрон витрины и склада — дело
         * обычное), товара на полке нет. Обратный случай — флаг `true` при
         * нулевом остатке — тоже [OutOfStock] по той же причине: обещать
         * наличие там, где склад пуст, значит отправить человека зря.
         */
        fun of(isAvailable: Boolean?, stockQuantity: Int?): ProductStock = when {
            isAvailable == false -> OutOfStock
            stockQuantity != null && stockQuantity <= 0 -> OutOfStock
            isAvailable == true || stockQuantity != null -> InStock
            else -> Unknown
        }
    }
}

/**
 * Товар аптечной витрины (issue #100, `pharmacy-controller`).
 *
 * Поля прочитаны из схемы стенда как есть: имя `ProductResponse` в
 * `/v3/api-docs` встречается только в двух путях одного контроллера, то есть
 * коллизии springdoc — той, из-за которой поля запроса приходилось выводить в
 * issue #76, #84 и #97, — здесь нет.
 *
 * **Купить товар нельзя, и кнопки «купить» тут не будет**: своей ручки заказа
 * у аптеки контроллер не отдаёт (`PHARMACY` значится только в `vertical` у
 * `GET /orders`), то есть корзину аптеки бэкенду сейчас нечем принять. Поле,
 * которое некуда отправить, — обещание, которого никто не выполнит (то же
 * правило, что применялось к промокоду в issue #9).
 *
 * @param priceSum цена. Дробного близнеца (как `balanceSom` у кошелька, issue
 * #62) в контракте нет, поэтому считаем сумами — как в «Еде» (issue #9) и в
 * записи к мастеру (issue #97).
 * @param stockQuantity остаток на складе. Показывается только тогда, когда
 * он мал: «осталось 2» — повод поспешить, а «осталось 340» ничего не решает и
 * читается как складская сводка.
 */
@Immutable
data class PharmacyProduct(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val dosageForm: String? = null,
    val strength: String? = null,
    val priceSum: Long? = null,
    val stockQuantity: Int? = null,
    val stock: ProductStock = ProductStock.Unknown,
    val requiresPrescription: Boolean = false,
) {
    /**
     * Форма выпуска и дозировка одной строкой: «tabletka, 500 mg». Порознь они
     * заняли бы две строки под названием, вместе читаются как подпись.
     */
    val formLabel: String?
        get() = listOfNotNull(dosageForm, strength)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

    /**
     * Остаток стоит называть, только когда он кончается. Порог выбран на глаз:
     * контракт про «мало» ничего не говорит, а показывать точное число всегда
     * — превращать витрину в складскую ведомость.
     */
    val showsStockQuantity: Boolean
        get() = stock == ProductStock.InStock &&
            stockQuantity != null &&
            stockQuantity in 1..LOW_STOCK_THRESHOLD

    companion object {
        const val LOW_STOCK_THRESHOLD = 5
    }
}

/**
 * Страница витрины. Пагинация у ручки **есть** (проверено живым запросом:
 * `?page=2&size=5` возвращает `page: 2`), поэтому фильтровать по уже
 * приехавшему списку нельзя — совпадения на непрогруженных страницах были бы
 * невидимы. Поиск идёт серверным параметром `query`.
 *
 * [hasMore] считается по `last`, при его отсутствии — по `page`/`totalPages`,
 * а полное молчание сервера о страницах догрузку **останавливает**: лучше не
 * показать хвост, чем крутить одну и ту же страницу в цикле (то же правило,
 * что у уведомлений в issue #81 и у своих заведений в issue #94).
 */
@Immutable
data class PharmacyProductPage(
    val items: List<PharmacyProduct> = emptyList(),
    val hasMore: Boolean = false,
)
