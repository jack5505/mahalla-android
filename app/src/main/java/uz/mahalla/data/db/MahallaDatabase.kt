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
 * `exportSchema = true` (issue #64): схема каждой версии лежит в
 * `app/schemas/` и коммитится вместе с кодом — без неё ни сравнить версии,
 * ни написать миграцию по факту, а не по памяти.
 *
 * Обновление схемы: поднять [VERSION], добавить `Migration` в
 * [MahallaMigrations.ALL] и случай в `MahallaMigrationsTest`. Миграции нет —
 * приложение упадёт при открытии БД на устройстве с прежней схемой (прежний
 * `fallbackToDestructiveMigration` вместо этого молча стирал корзину).
 */
@Database(
    entities = [
        PlaceEntity::class,
        OrderEntity::class,
        CartDraftItemEntity::class,
    ],
    // v2 — эпик 4: в кэш мест добавлены адрес, координаты, фото и контакты.
    // v3 — эпик 5: строка черновика корзины ключуется позицией + модификаторами.
    version = MahallaDatabase.VERSION,
    exportSchema = true,
)
abstract class MahallaDatabase : RoomDatabase() {

    abstract fun placeDao(): PlaceDao
    abstract fun orderDao(): OrderDao
    abstract fun cartDraftDao(): CartDraftDao

    companion object {
        const val NAME = "mahalla.db"

        /** Текущая версия схемы. Константа, чтобы тест миграций сверялся с ней. */
        const val VERSION = 3
    }
}
