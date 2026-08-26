package uz.mahalla.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Кэш каталога мест (эпик 1.4, расширен в 4.х): по нему discovery и карточка
 * открываются без сети. `updatedAtEpochSeconds` нужен, чтобы отличать свежий
 * кэш от устаревшего.
 *
 * Детальные поля (описание, телефон, фото) кэшируются тоже: карточка,
 * открытая офлайн, иначе показывала бы одно название. Расписание и отзывы в
 * кэш не попадают — они устаревают быстрее всего, и лучше показать их
 * отсутствие, чем вчерашние часы работы.
 */
@Entity(
    tableName = "places",
    indices = [Index("category")],
)
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val rating: Double,
    val distanceMeters: Int,
    val isOpenNow: Boolean,
    val updatedAtEpochSeconds: Long,
    val reviewCount: Int = 0,
    val address: String? = null,
    val photoUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isRecommended: Boolean = false,
    val description: String? = null,
    val phone: String? = null,
    val website: String? = null,
)
