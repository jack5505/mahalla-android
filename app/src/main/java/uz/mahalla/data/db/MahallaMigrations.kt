package uz.mahalla.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграции локальной БД (issue #64).
 *
 * До этого стоял `fallbackToDestructiveMigration()`: каждое обновление
 * приложения, поднимавшее `version`, молча пересоздавало БД — вместе с
 * черновиком корзины. Кэш каталога потерять не жалко (он и так протухает по
 * TTL), а собранная корзина — это работа пользователя, и пропадала она без
 * единого слова.
 *
 * Правило на будущее: **новая `version` — новая `Migration` в [ALL]**, иначе
 * приложение упадёт при первом же открытии БД на устройстве с прежней схемой.
 * Это лучше молчаливой потери данных, а тест `MahallaMigrationsTest` ловит
 * пропуск ещё в CI: он открывает БД каждой прежней версии и проверяет, что
 * данные доехали, а схема после миграций совпала с ожидаемой Room'ом.
 */
object MahallaMigrations {

    /**
     * v1 → v2 (эпик 4): в кэш мест добавлены счётчик отзывов, адрес,
     * координаты, фото, флаг «рекомендуем» и контакты.
     *
     * Значения `DEFAULT` нужны только на время `ALTER TABLE`: у сущности
     * дефолтов нет (`@ColumnInfo(defaultValue = …)` не проставлен), а Room
     * сверяет их лишь тогда, когда они объявлены с обеих сторон.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `places` ADD COLUMN `reviewCount` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `address` TEXT")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `photoUrl` TEXT")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `latitude` REAL")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `longitude` REAL")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `isRecommended` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `description` TEXT")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `phone` TEXT")
            db.execSQL("ALTER TABLE `places` ADD COLUMN `website` TEXT")
        }
    }

    /**
     * v2 → v3 (эпик 5): строка черновика корзины ключуется не позицией, а
     * `lineId` (позиция + выбранные модификаторы), и хранит заведение, доставку
     * и сами модификаторы.
     *
     * Смена первичного ключа в SQLite делается только пересозданием таблицы,
     * поэтому строки переносятся копированием. `lineId` старой строки равен её
     * `productId`: модификаторов до v3 не было, а `CartCalculator.lineId`
     * ровно это и возвращает для позиции без них — то есть перенесённая
     * корзина продолжает складываться с новыми добавлениями, а не двоится.
     *
     * `placeName` и `deliverySum` заполняются пустыми: их неоткуда взять, а
     * корзина показывает их до загрузки меню и обновляет после.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `cart_draft_items_new` (
                    `placeId` TEXT NOT NULL,
                    `lineId` TEXT NOT NULL,
                    `productId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `priceSum` INTEGER NOT NULL,
                    `quantity` INTEGER NOT NULL,
                    `placeName` TEXT NOT NULL,
                    `deliverySum` INTEGER NOT NULL,
                    `optionIds` TEXT NOT NULL,
                    `optionsLabel` TEXT NOT NULL,
                    PRIMARY KEY(`placeId`, `lineId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `cart_draft_items_new` (
                    `placeId`, `lineId`, `productId`, `name`, `priceSum`, `quantity`,
                    `placeName`, `deliverySum`, `optionIds`, `optionsLabel`
                )
                SELECT `placeId`, `productId`, `productId`, `name`, `priceSum`, `quantity`,
                       '', 0, '', ''
                FROM `cart_draft_items`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `cart_draft_items`")
            db.execSQL("ALTER TABLE `cart_draft_items_new` RENAME TO `cart_draft_items`")
        }
    }

    /** Все миграции по порядку — этот список уходит в `Room.databaseBuilder`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
