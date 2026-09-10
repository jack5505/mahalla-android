package uz.mahalla.feature.food.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.data.DeliveryFeeRepository

/**
 * Стоимость доставки для корзины и чекаута (issue #179) — один загрузчик на
 * два экрана: правило «когда спрашивать» не должно расходиться между ними.
 *
 * Изменение суммы позиций **сначала стирает прежнюю цену** и только потом
 * запрашивает новую: доставка зависит от суммы (на стенде от 2 000 сум она
 * бесплатна), поэтому «старая цена на новую корзину» — то же расхождение
 * показанного и списанного, из-за которого задача и появилась. Итог в это
 * время — сумма позиций.
 *
 * Первый запрос уходит сразу, повторные — с задержкой [DEBOUNCE_MS]: сумма
 * меняется на каждое «+» и «−», и запрос на каждый тик стоил бы серии
 * ответов, из которых пригодится последний. Ждать же первую цену полсекунды
 * незачем — её человек и читает (так же сделан поиск, `SearchViewModel`).
 *
 * Дедупликация — в пределах одного экрана и одного способа получения: та же
 * сумма подряд второй раз не запрашивается. Новый экран создаёт новый
 * загрузчик, а самовывоз забывает всё ([forget]), поэтому возврат к доставке
 * спрашивает заново.
 *
 * @param onFee куда положить результат. `null` — доставка неизвестна (сервер
 * не назвал ключа, запрос не удался, сумма изменилась или доставки в этом
 * заказе нет): экран в этом случае показывает то же, что до issue #179, —
 * итог без доставки, а не ошибку. Оформление обязано остаться доступным.
 */
class DeliveryFeeLoader(
    private val repository: DeliveryFeeRepository,
    private val scope: CoroutineScope,
    private val onFee: (Long?) -> Unit,
) {

    private var job: Job? = null

    /** Сумма позиций, для которой доставка уже запрошена; `null` — ничего. */
    private var requestedSum: Long? = null

    /**
     * @param itemsSum сумма позиций в целых сумах.
     * @param needed нужна ли доставка вообще: у самовывоза и «на месте» её нет,
     * и спрашивать цену того, чего в заказе не будет, незачем.
     */
    fun refresh(itemsSum: Long, needed: Boolean) {
        if (!needed || itemsSum <= 0) {
            forget()
            return
        }
        if (itemsSum == requestedSum) return

        val isFirst = requestedSum == null
        requestedSum = itemsSum
        job?.cancel()
        // Прежняя цена относилась к другой корзине: держать её в итоге нельзя
        // даже те полсекунды, что летит новый запрос.
        onFee(null)
        job = scope.launch {
            if (!isFirst) delay(DEBOUNCE_MS)
            when (val result = repository.deliveryFee(itemsSum)) {
                // Сбрасываем и запомненную сумму, чтобы следующее изменение
                // корзины повторило попытку.
                is ApiResult.Failure -> {
                    requestedSum = null
                    onFee(null)
                }

                is ApiResult.Success -> onFee(result.data)
            }
        }
    }

    private fun forget() {
        job?.cancel()
        job = null
        requestedSum = null
        onFee(null)
    }

    companion object {
        const val DEBOUNCE_MS = 400L
    }
}
