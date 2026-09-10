package uz.mahalla.feature.food.data

import kotlinx.serialization.json.JsonPrimitive
import uz.mahalla.core.format.Money
import uz.mahalla.core.format.tiyinToSom
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стоимость доставки до оформления заказа (issue #179).
 *
 * Раньше корзина показывала ноль и объясняла это тем, что до оформления
 * стоимость доставки бэкенд не сообщает. `GET food/delivery-fee` это меняет:
 * ручка есть, отвечает анонимно и зависит от суммы позиций (на стенде от
 * 2 000 сум доставка бесплатна) — значит человек может видеть настоящий итог
 * до того, как подтвердит заказ.
 *
 * Заведения в запросе нет: правило платформы, а не тариф ресторана.
 *
 * **Итог заказа всё равно считает сервер.** То, что здесь, — оценка для
 * экрана; суммы оформленного заказа экран статуса берёт из `GET orders/{id}`
 * и не смешивает с этой.
 */
interface DeliveryFeeRepository {

    /**
     * Стоимость доставки для корзины на [itemsSum] **целых сумов**.
     *
     * `null` в успешном ответе — сервер ключа не назвал: доставка неизвестна.
     * Это не ноль: «бесплатно» и «не знаем» на экране выглядят по-разному, и
     * подменять одно другим значит обещать бесплатную доставку наугад.
     */
    suspend fun deliveryFee(itemsSum: Long): ApiResult<Long?>

    companion object {
        /** Ключ в карте `data`: у ручки нет DTO, значение берётся по имени. */
        const val DELIVERY_AMOUNT_KEY = "deliveryAmount"
    }
}

@Singleton
class DefaultDeliveryFeeRepository @Inject constructor(
    private val api: FoodApi,
) : DeliveryFeeRepository {

    override suspend fun deliveryFee(itemsSum: Long): ApiResult<Long?> =
        apiCall { api.deliveryFee(Money.somToTiyin(itemsSum)).payload() }.map { amounts ->
            // Значение читается мягко: не число под этим ключом — та же
            // «доставка неизвестна», а не ошибка экрана. Перевод тийинов в
            // сумы — здесь и только здесь, как во всех мапперах вертикали
            // (issue #149); зажимать отрицательное не нужно, итог считает
            // `CartCalculator.totals`.
            (amounts[DeliveryFeeRepository.DELIVERY_AMOUNT_KEY] as? JsonPrimitive)
                ?.content
                ?.toLongOrNull()
                ?.tiyinToSom()
        }
}
