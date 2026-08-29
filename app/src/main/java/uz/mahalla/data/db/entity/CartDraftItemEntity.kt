package uz.mahalla.data.db.entity

import androidx.room.Entity

/**
 * Черновик корзины (эпик 1.4, расширен в 5.2): должен переживать закрытие
 * приложения, поэтому лежит в Room, а не в памяти ViewModel.
 *
 * Ключ строки — не id позиции, а [lineId] (позиция + выбранные модификаторы,
 * см. `CartCalculator.lineId`): одно и то же блюдо с разными добавками — это
 * две строки корзины с разной ценой.
 *
 * [placeName] и [deliverySum] денормализованы намеренно: корзину показывают до
 * загрузки меню и без сети, а идти за названием заведения в кэш каталога значит
 * зависеть от того, что его оттуда не вычистили по TTL.
 */
@Entity(
    tableName = "cart_draft_items",
    primaryKeys = ["placeId", "lineId"],
)
data class CartDraftItemEntity(
    val placeId: String,
    val lineId: String,
    val productId: String,
    val name: String,
    val priceSum: Long,
    val quantity: Int,
    val placeName: String = "",
    val deliverySum: Long = 0,
    /** Id модификаторов через запятую; пусто — позиция без них. */
    val optionIds: String = "",
    val optionsLabel: String = "",
)
