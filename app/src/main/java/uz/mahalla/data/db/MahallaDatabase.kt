package uz.mahalla.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.db.entity.PlaceEntity

/**
 * Локальная БД (эпик 1.4): кэш каталога и заказов + черновик корзины.
 *
 * `exportSchema = false` — схема ещё не стабилизирована; включим экспорт и
 * миграции, когда появится первый релиз.
 */
@Database(
    entities = [
        PlaceEntity::class,
        OrderEntity::class,
        CartDraftItemEntity::class,
    ],
    // v2 — эпик 4: в кэш мест добавлены адрес, координаты, фото и контакты.
    // v3 — эпик 5: строка черновика корзины ключуется позицией + модификаторами.
    version = 3,
    exportSchema = false,
)
abstract class MahallaDatabase : RoomDatabase() {

    abstract fun placeDao(): PlaceDao
    abstract fun orderDao(): OrderDao
    abstract fun cartDraftDao(): CartDraftDao

    companion object {
        const val NAME = "mahalla.db"
    }
}
