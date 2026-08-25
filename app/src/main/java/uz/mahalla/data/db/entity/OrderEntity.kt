package uz.mahalla.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Кэш заказов (эпик 1.4). Статус хранится строкой: список статусов задаёт
 * бэкенд, и неизвестное значение не должно валить чтение кэша.
 * Суммы — целые сумы (см. `MoneyFormatter`).
 */
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val placeId: String,
    val placeName: String,
    val status: String,
    val totalSum: Long,
    val createdAtEpochSeconds: Long,
)
