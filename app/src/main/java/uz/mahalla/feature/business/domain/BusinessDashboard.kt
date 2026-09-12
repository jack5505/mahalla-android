package uz.mahalla.feature.business.domain

import uz.mahalla.core.format.tiyinToSom
import java.util.Locale

/**
 * Метрика дня на дашборде (задача 12.1).
 *
 * **Состав метрик задаёт сервер, а не приложение.** `GET
 * analytics/places/{placeId}/dashboard` отдаёт `Map<String, Long>` —
 * в `/v3/api-docs` это `additionalProperties`, то есть имена ключей схема не
 * описывает вовсе. Придумать их и разложить ответ по фиксированным полям
 * значило бы получить пустой дашборд на первом же расхождении, поэтому
 * приложение показывает **то, что приехало**: ключ сервера + его значение.
 *
 * @param key ключ ровно как его прислал сервер — он же ключ строки списка.
 * @param value значение; отрицательные не отбрасываются (счётчик возвратов
 * вполне может быть со знаком), а приводятся к тексту как есть.
 * @param kind как это показать. Не утверждение о контракте, а **правило
 * показа**: по имени ключа видно, деньги это или счётчик, и сумма без разрядов
 * читается хуже, чем с ними. Ошибка тут стоит неудачного форматирования, а не
 * потерянной метрики.
 */
data class BusinessMetric(
    val key: String,
    val value: Long,
    val kind: BusinessMetricKind = BusinessMetricKind.of(key),
) {

    /**
     * Подпись, когда ключ незнаком: `total_revenue` → «Total revenue».
     *
     * Перевода тут быть не может — слово придумал сервер, — но показать
     * человеку сырой `total_revenue` хуже, чем то же самое словами.
     */
    val fallbackLabel: String
        get() {
            val words = key.replace('_', ' ')
                .replace(CAMEL_HUMP, " ")
                .trim()
                .lowercase(Locale.ROOT)
            if (words.isEmpty()) return key
            return words.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }

    private companion object {
        val CAMEL_HUMP = Regex("(?<=[a-z0-9])(?=[A-Z])")
    }
}

/** Как форматировать значение метрики. */
enum class BusinessMetricKind {
    /** Сумма в сумах — с разрядами и валютой. */
    Money,

    /** Счётчик: заказы, талоны, просмотры. */
    Count,
    ;

    companion object {
        /**
         * Деньги узнаются по имени ключа. Список намеренно широкий и в обоих
         * написаниях (`totalRevenue` и `total_revenue`): промах в сторону
         * «счётчика» показывает сумму без разрядов, промах в другую сторону —
         * приписывает «so'm» к числу заказов, и второе заметнее.
         */
        private val MONEY_HINTS = listOf("revenue", "amount", "sum", "income", "earning", "total")

        /**
         * `totalOrders` — счётчик, хотя и содержит «total». Поэтому явные
         * счётные слова сильнее денежных: они стоят рядом с сущностью, а
         * «total» — это только «за всё время».
         */
        private val COUNT_HINTS = listOf("count", "orders", "views", "tickets", "clients", "rating")

        fun of(key: String): BusinessMetricKind {
            val normalized = key.lowercase(Locale.ROOT)
            if (COUNT_HINTS.any(normalized::contains)) return Count
            if (MONEY_HINTS.any(normalized::contains)) return Money
            return Count
        }
    }
}

/**
 * Дашборд заведения: метрики дня в том порядке, в каком их прислал сервер.
 *
 * Свой порядок приложение не наводит: сервер знает, что важнее для этой
 * категории заведений, а алфавитная сортировка перемешала бы выручку с
 * просмотрами при каждом новом ключе.
 */
data class BusinessDashboard(
    val metrics: List<BusinessMetric> = emptyList(),
) {
    val isEmpty: Boolean get() = metrics.isEmpty()

    companion object {
        /**
         * Ключ без имени выбрасывается: показать пустую строку с числом
         * нельзя — непонятно, что это за число, — а в `LazyColumn` пустой ключ
         * ещё и станет дубликатом при втором таком же.
         *
         * `null`-значения (`Map<String, Long>` в JSON вполне может приехать с
         * `null`) отбрасываются по той же причине: «—» вместо метрики
         * читается как ноль, а это разные вещи.
         */
        fun from(raw: Map<String, Long?>): BusinessDashboard = BusinessDashboard(
            metrics = raw.mapNotNull { (key, value) ->
                val name = key.trim()
                if (name.isEmpty() || value == null) return@mapNotNull null
                val kind = BusinessMetricKind.of(name)
                // Деньги сервер отдаёт в тийинах, как и весь остальной проект
                // (issue #149) — счётчики (заказы, просмотры) делить не на что.
                val amount = if (kind == BusinessMetricKind.Money) value.tiyinToSom() else value
                BusinessMetric(key = name, value = amount, kind = kind)
            },
        )
    }
}
