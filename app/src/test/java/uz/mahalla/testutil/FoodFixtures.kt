package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.MenuRepository
import uz.mahalla.feature.food.data.OrderRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.MenuCategory
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.feature.food.domain.MenuOption
import uz.mahalla.feature.food.domain.OptionGroup
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.food.domain.PromoCode
import java.time.Instant

/** Позиция меню с разумными значениями — тесты задают только важное. */
fun menuItem(
    id: String,
    name: String = "Item $id",
    priceSum: Long = 30_000,
    isAvailable: Boolean = true,
    optionGroups: List<OptionGroup> = emptyList(),
): MenuItem = MenuItem(
    id = id,
    name = name,
    priceSum = priceSum,
    isAvailable = isAvailable,
    optionGroups = optionGroups,
)

fun optionGroup(
    id: String,
    minChoices: Int = 0,
    maxChoices: Int = 1,
    options: List<MenuOption>,
): OptionGroup = OptionGroup(
    id = id,
    name = "Group $id",
    minChoices = minChoices,
    maxChoices = maxChoices,
    options = options,
)

fun menuOption(
    id: String,
    priceDeltaSum: Long = 0,
    isAvailable: Boolean = true,
): MenuOption = MenuOption(
    id = id,
    name = "Option $id",
    priceDeltaSum = priceDeltaSum,
    isAvailable = isAvailable,
)

fun menu(
    placeId: String = "place-1",
    items: List<MenuItem> = listOf(menuItem("osh")),
    deliverySum: Long = 0,
): Menu = Menu(
    placeId = placeId,
    placeName = "Osh markazi",
    categories = listOf(MenuCategory(id = "main", name = "Asosiy", items = items)),
    deliverySum = deliverySum,
)

fun cartLine(
    itemId: String,
    name: String = "Item $itemId",
    unitPriceSum: Long = 30_000,
    quantity: Int = 1,
    optionIds: Set<String> = emptySet(),
): CartLine = CartLine(
    id = CartCalculator.lineId(itemId, optionIds),
    itemId = itemId,
    name = name,
    unitPriceSum = unitPriceSum,
    quantity = quantity,
    optionIds = optionIds,
)

fun order(
    id: String = "o-1",
    status: OrderStatus = OrderStatus.Created,
    method: DeliveryMethod = DeliveryMethod.Delivery,
    lines: List<CartLine> = listOf(cartLine("osh", quantity = 2)),
): Order = Order(
    id = id,
    placeId = "place-1",
    placeName = "Osh markazi",
    status = status,
    method = method,
    payment = PaymentMethod.Wallet,
    totals = CartTotals(subtotalSum = CartCalculator.subtotal(lines)),
    lines = lines,
    createdAt = Instant.parse("2026-08-26T10:00:00Z"),
)

/** Меню и промокод под тесты ViewModel — без MockWebServer. */
class FakeMenuRepository : MenuRepository {

    var menuResult: ApiResult<Menu> = ApiResult.Success(menu())
    var promoResult: ApiResult<PromoCode> = ApiResult.Failure(ApiError.NotFound)

    val promoRequests: MutableList<Triple<String, String, Long>> = mutableListOf()

    override suspend fun menu(placeId: String): ApiResult<Menu> = menuResult

    override suspend fun promo(
        placeId: String,
        code: String,
        subtotalSum: Long,
    ): ApiResult<PromoCode> {
        promoRequests += Triple(placeId, code, subtotalSum)
        return promoResult
    }
}

/**
 * Корзина в памяти. Правила количества и ключа строки — те же
 * ([CartCalculator]), иначе фейк проверял бы не то поведение, что реализация.
 */
class FakeCartRepository : CartRepository {

    private val carts = MutableStateFlow<Map<String, Cart>>(emptyMap())

    var clearedPlaceIds: MutableList<String> = mutableListOf()
        private set

    fun seed(cart: Cart) {
        carts.value = carts.value + (cart.placeId to cart)
    }

    fun current(placeId: String): Cart = carts.value[placeId] ?: Cart(placeId, "")

    override fun cart(placeId: String): Flow<Cart> =
        carts.map { it[placeId] ?: Cart(placeId, "") }

    override suspend fun activePlaceId(): String? =
        carts.value.values.firstOrNull { it.lines.isNotEmpty() }?.placeId

    override suspend fun snapshot(placeId: String): Cart = current(placeId)

    override suspend fun add(
        placeId: String,
        placeName: String,
        deliverySum: Long,
        line: CartLine,
    ) {
        val cart = current(placeId)
        seed(
            cart.copy(
                placeName = placeName.ifBlank { cart.placeName },
                deliverySum = deliverySum,
                lines = CartCalculator.add(cart.lines, line),
            ),
        )
    }

    /** Room недоступен: замена черновика падает, прежний остаётся на месте. */
    var replaceFails: Boolean = false

    override suspend fun replace(
        placeId: String,
        placeName: String,
        deliverySum: Long,
        lines: List<CartLine>,
    ) {
        if (replaceFails) error("cart draft is not writable")
        carts.value = mapOf(
            placeId to Cart(
                placeId = placeId,
                placeName = placeName,
                lines = lines,
                deliverySum = deliverySum,
            ),
        )
    }

    override suspend fun setQuantity(placeId: String, lineId: String, quantity: Int) {
        val cart = current(placeId)
        seed(cart.copy(lines = CartCalculator.setQuantity(cart.lines, lineId, quantity)))
    }

    override suspend fun remove(placeId: String, lineId: String) {
        val cart = current(placeId)
        seed(cart.copy(lines = CartCalculator.remove(cart.lines, lineId)))
    }

    override suspend fun clear(placeId: String) {
        clearedPlaceIds += placeId
        seed(current(placeId).copy(lines = emptyList(), promo = null))
    }

    override suspend fun clearAll() {
        clearedPlaceIds += carts.value.keys
        carts.value = emptyMap()
    }

    override fun applyPromo(promo: PromoCode?) {
        carts.value = carts.value.mapValues { (_, cart) -> cart.copy(promo = promo) }
    }
}

class FakeOrderRepository : OrderRepository {

    var created: ApiResult<Order> = ApiResult.Success(order())
    var loaded: ApiResult<Order> = ApiResult.Success(order())
    var cancelled: ApiResult<Order> = ApiResult.Success(order(status = OrderStatus.Cancelled))

    /** Очередь ответов опроса; пусто — отдаётся [loaded]. */
    val pollResponses: ArrayDeque<ApiResult<Order>> = ArrayDeque()

    var createdWith: Pair<Cart, CheckoutForm>? = null
        private set

    var repeatedOrderId: String? = null
        private set

    var loadCount: Int = 0
        private set

    override suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<Order> {
        createdWith = cart to form
        return created
    }

    override suspend fun order(orderId: String): ApiResult<Order> {
        loadCount++
        return pollResponses.removeFirstOrNull() ?: loaded
    }

    override suspend fun cancel(orderId: String): ApiResult<Order> = cancelled

    /** `null` — база недоступна: проверяем, что экран остаётся на месте. */
    var repeatFails: Boolean = false

    override suspend fun repeat(order: Order): List<CartLine>? {
        repeatedOrderId = order.id
        return order.lines.takeUnless { repeatFails }
    }
}
