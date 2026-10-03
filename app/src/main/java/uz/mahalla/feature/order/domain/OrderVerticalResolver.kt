package uz.mahalla.feature.order.domain

import javax.inject.Inject
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.food.data.FoodApi

/**
 * Вертикаль заказа по его id (issue #343): у deep link'а `mahalla://order/{id}`
 * и у пуша `ORDER_STATUS_UPDATED` вертикали нет, только id, а заказ может быть
 * «Еды», «Одежды» или «Аптеки».
 *
 * Ходит через [FoodApi.order] — не потому что заказ обязательно еды, а потому
 * что `GET orders/{orderId}` общая ручка на все вертикали (issue #108,
 * подтверждено `docs/API-CONTRACT.md`); там же она и объявлена по историческим
 * причинам (коллизия схемы `OrderResponse` в springdoc).
 *
 * [ActivityKind] переиспользован из «моих активностей» (issue #73) — там уже
 * есть готовое сопоставление `vertical` → вид заказа, заводить второе не нужно.
 */
interface OrderVerticalResolver {
    suspend fun resolve(orderId: String): ApiResult<ActivityKind>
}

class DefaultOrderVerticalResolver @Inject constructor(
    private val foodApi: FoodApi,
) : OrderVerticalResolver {

    override suspend fun resolve(orderId: String): ApiResult<ActivityKind> =
        apiCall { foodApi.order(orderId).payload() }
            .map { order -> ActivityKind.ofOrderVertical(order.vertical) }
}
