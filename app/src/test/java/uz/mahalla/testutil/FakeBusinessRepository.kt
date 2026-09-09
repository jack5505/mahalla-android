package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessMenu
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.NewMenuItemForm
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole

/**
 * Бизнес-панель в памяти (эпик #16): экраны проверяются без MockWebServer.
 *
 * Каждый исход задаётся отдельно, а всё ушедшее в репозиторий записывается:
 * тест по правам обязан уметь доказать, что запроса **не было** — иначе
 * «доступ закрыт» не отличить от «доступ закрыт, но метрики всё равно
 * спросили».
 */
class FakeBusinessRepository : BusinessRepository {

    var accessResult: ApiResult<BusinessAccess> = ApiResult.Success(
        BusinessAccess(
            placeId = PLACE_ID,
            placeName = "Osh Markazi",
            category = PlaceCategory.Food,
            role = PlaceStaffRole.Owner,
            status = PlaceModerationStatus.Active,
            isAvailable = true,
        ),
    )

    var dashboardResult: ApiResult<BusinessDashboard> =
        ApiResult.Success(BusinessDashboard.from(mapOf("orders" to 12L)))

    var queueResult: ApiResult<List<QueueEntry>> = ApiResult.Success(emptyList())

    /** Исход действия над талоном; `null` — вернуть [actResult] по умолчанию. */
    var actResult: ((QueueEntry, QueueAction) -> ApiResult<QueueEntry>)? = null

    var pauseResult: ApiResult<Boolean>? = null

    val orderPages: MutableMap<Pair<String?, Int>, ApiResult<BusinessOrderPage>> = mutableMapOf()
    var defaultOrderPage: ApiResult<BusinessOrderPage> = ApiResult.Success(BusinessOrderPage())

    var updateOrderResult: ApiResult<BusinessOrder>? = null

    var menuResult: ApiResult<BusinessMenu> = ApiResult.Success(BusinessMenu())
    var toggleStopListResult: ApiResult<Boolean>? = null
    var createItemResult: ApiResult<BusinessMenu>? = null

    val accessRequests = mutableListOf<String>()
    val dashboardRequests = mutableListOf<String>()
    val queueRequests = mutableListOf<String>()
    val actions = mutableListOf<Triple<String, String, QueueAction>>()
    val paused = mutableListOf<Pair<String, Boolean>>()
    val orderRequests = mutableListOf<Pair<String?, Int>>()
    val statusUpdates = mutableListOf<Pair<String, OrderStatus>>()
    val toggledItems = mutableListOf<Pair<String, Boolean>>()
    val createdItems = mutableListOf<NewMenuItemForm>()

    /** Талоны, из которых `act` берёт исходный: без них исход не собрать. */
    val knownEntries = mutableMapOf<String, QueueEntry>()

    /**
     * Задержка ответа `act`: пока `deferred` не завершён, запрос «в полёте».
     * Иначе на `UnconfinedTestDispatcher` ответ приходит мгновенно, и
     * состояние «идёт запрос» не поймать вовсе.
     */
    var actGate: CompletableDeferred<Unit>? = null

    override suspend fun access(placeId: String): ApiResult<BusinessAccess> {
        accessRequests += placeId
        return accessResult
    }

    override suspend fun dashboard(placeId: String): ApiResult<BusinessDashboard> {
        dashboardRequests += placeId
        return dashboardResult
    }

    override suspend fun queue(placeId: String): ApiResult<List<QueueEntry>> {
        queueRequests += placeId
        val result = queueResult
        if (result is ApiResult.Success) {
            result.data.forEach { knownEntries[it.id] = it }
        }
        return result
    }

    override suspend fun act(
        placeId: String,
        ticketId: String,
        action: QueueAction,
    ): ApiResult<QueueEntry> {
        actions += Triple(placeId, ticketId, action)
        actGate?.await()
        val entry = knownEntries[ticketId]
            ?: return ApiResult.Failure(ApiError.Business("NOT_FOUND"))
        return actResult?.invoke(entry, action)
            ?: ApiResult.Success(entry.copy(status = action.expectedStatus()))
    }

    override suspend fun togglePause(placeId: String, current: Boolean): ApiResult<Boolean> {
        paused += placeId to current
        return pauseResult ?: ApiResult.Success(!current)
    }

    override suspend fun orders(
        placeId: String,
        status: String?,
        page: Int,
        size: Int,
    ): ApiResult<BusinessOrderPage> {
        orderRequests += status to page
        return orderPages[status to page] ?: defaultOrderPage
    }

    override suspend fun updateOrderStatus(
        placeId: String,
        orderId: String,
        status: OrderStatus,
    ): ApiResult<BusinessOrder> {
        statusUpdates += orderId to status
        return updateOrderResult ?: ApiResult.Failure(ApiError.Business("NOT_STUBBED"))
    }

    override suspend fun menu(placeId: String): ApiResult<BusinessMenu> = menuResult

    override suspend fun toggleStopList(itemId: String, current: Boolean): ApiResult<Boolean> {
        toggledItems += itemId to current
        return toggleStopListResult ?: ApiResult.Success(!current)
    }

    override suspend fun createItem(
        placeId: String,
        form: NewMenuItemForm,
    ): ApiResult<BusinessMenu> {
        createdItems += form
        return createItemResult ?: menuResult
    }

    companion object {
        const val PLACE_ID = "p-1"
    }
}

/** Чем кончается действие на сервере — по переходам walk-in бэкенда. */
private fun QueueAction.expectedStatus() = when (this) {
    QueueAction.Accept -> uz.mahalla.feature.queue.domain.WalkInStatus.Waiting
    QueueAction.Decline -> uz.mahalla.feature.queue.domain.WalkInStatus.Declined
    QueueAction.Start -> uz.mahalla.feature.queue.domain.WalkInStatus.InChair
    QueueAction.Complete -> uz.mahalla.feature.queue.domain.WalkInStatus.Completed
}
