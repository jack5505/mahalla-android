package uz.mahalla.feature.business.domain

import uz.mahalla.feature.food.domain.OrderStatus
import java.util.Locale

/**
 * Период дашборда (задача 12.1): переключатель на экране, на сервере —
 * `period` query-параметром `GET analytics/places/{placeId}/dashboard`
 * (схема `SellerDashboardResponse` подтверждена живым `/v3/api-docs`
 * 2026-10-06, issue #292). По умолчанию — «День»: задача называется «Дашборд
 * — метрики дня».
 */
enum class DashboardPeriod(val apiValue: String) {
    Day("DAY"),
    Week("WEEK"),
    Month("MONTH"),
}

/**
 * Тип позиции в топе товаров/услуг. Значения — перечисление `itemType` того
 * же стенда (`ItemView`, issue #292): `MENU_ITEM`, `CLOTHING_VARIANT`,
 * `DRUG`, `TICKET`.
 *
 * [Unknown] обязателен: `TopItemStat.itemType` в схеме объявлен просто
 * строкой — своего перечисления у поля нет, и пятое значение, которое бэкенд
 * заведёт раньше приложения, не должно уронить список.
 */
enum class BusinessItemType {
    MenuItem,
    ClothingVariant,
    Drug,
    Ticket,
    Unknown,
    ;

    companion object {
        fun fromApi(value: String?): BusinessItemType =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "MENU_ITEM" -> MenuItem
                "CLOTHING_VARIANT" -> ClothingVariant
                "DRUG" -> Drug
                "TICKET" -> Ticket
                else -> Unknown
            }
    }
}

/** Заказы в одном статусе за период: сколько и на какую сумму (`StatusOrderStat`). */
data class BusinessOrderStatusStat(
    val status: OrderStatus,
    val orderCount: Long,
    val totalAmountSum: Long,
)

/** Строка топа товаров/услуг за период (`TopItemStat`). */
data class BusinessTopItem(
    val itemId: String,
    val itemType: BusinessItemType,
    val name: String,
    val quantity: Long,
    val totalAmountSum: Long,
)

/**
 * Дашборд заведения за период (задача 12.1, `SellerDashboardResponse`).
 *
 * Выручка — [revenueSum], **не** приводится к нулю снизу: возвраты вполне
 * могут увести период в минус, и спрятать это значило бы показать владельцу
 * неверную картину. Остальные суммы (по статусам, по товарам) — это сложенные
 * чеки, отрицательными не бывают.
 *
 * [isEmpty] — период, в который ничего не продали: ни заказов, ни разбивки по
 * статусам, ни позиций в топе, ни ненулевой выручки. Условие по выручке — не
 * на всякий случай: возврат за заказ из прошлого периода может увести
 * [revenueSum] в минус и при нуле заказов в этом периоде, и спрятать такую
 * сумму за «показателей пока нет» значило бы ровно то, от чего предостерегает
 * KDoc [revenueSum] выше. Это не ошибка и не повод прятать переключатель
 * периода — пустой «День» рядом с полным «Месяцем» для нового заведения
 * обычное дело.
 */
data class BusinessDashboard(
    val period: DashboardPeriod,
    val revenueSum: Long = 0,
    val totalOrders: Long = 0,
    val ordersByStatus: List<BusinessOrderStatusStat> = emptyList(),
    val topItems: List<BusinessTopItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = revenueSum == 0L &&
            totalOrders == 0L &&
            ordersByStatus.isEmpty() &&
            topItems.isEmpty()
}
