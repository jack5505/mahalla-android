package uz.mahalla.feature.fashion.domain

import uz.mahalla.feature.food.domain.Order

/**
 * Заказ одежды — это тот же [Order], что и у «Еды» (issue #108).
 *
 * У бэкенда общий `order-controller` и одна схема `OrderView` на все
 * вертикали: `CLOTHING` — просто значение её поля `vertical`. Своя копия
 * модели заказа разошлась бы с первой при первой же правке контракта — ровно
 * поэтому же вертикаль «Больницы» переиспользует модель записи вертикали
 * «Бронь» (issue #99).
 *
 * Что здесь своё — только страница списка: у «Еды» списка заказов нет вовсе,
 * заказ там открывается сразу после оформления.
 */
data class FashionOrderPage(
    val items: List<Order> = emptyList(),
    val hasMore: Boolean = false,
)

/** Значение `vertical` бэкенда, по которому из общего списка берутся заказы одежды. */
const val CLOTHING_VERTICAL = "CLOTHING"
