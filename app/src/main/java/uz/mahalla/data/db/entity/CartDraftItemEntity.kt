package uz.mahalla.data.db.entity

import androidx.room.Entity

/**
 * Черновик корзины (эпик 1.4): должен переживать закрытие приложения, поэтому
 * лежит в Room, а не в памяти ViewModel.
 *
 * Составной ключ (заведение + позиция) — корзина всегда в рамках одного
 * заведения, но черновиков может быть несколько.
 */
@Entity(
    tableName = "cart_draft_items",
    primaryKeys = ["placeId", "productId"],
)
data class CartDraftItemEntity(
    val placeId: String,
    val productId: String,
    val name: String,
    val priceSum: Long,
    val quantity: Int,
)
