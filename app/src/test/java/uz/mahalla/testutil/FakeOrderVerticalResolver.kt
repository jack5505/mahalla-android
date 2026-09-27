package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.order.domain.OrderVerticalResolver

class FakeOrderVerticalResolver(
    var result: ApiResult<ActivityKind> = ApiResult.Success(ActivityKind.FoodOrder),
) : OrderVerticalResolver {

    var lastOrderId: String? = null
        private set

    var callCount: Int = 0
        private set

    override suspend fun resolve(orderId: String): ApiResult<ActivityKind> {
        lastOrderId = orderId
        callCount++
        return result
    }
}
