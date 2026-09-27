package uz.mahalla.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Кэш включённых категорий каталога (issue #378) — ответ `GET categories`
 * как есть, чтобы главная, фильтр и анкета продавца между запусками читали
 * список из базы, а не из зашитого перечисления.
 *
 * Хранятся все коды, включая те, которых приложение ещё не знает: когда
 * появится плитка под `BAKERY`, она возьмётся из этого же кэша. Подписи
 * сервера лежат здесь на будущее — экран пока рисует свои строки по коду.
 */
@Entity(tableName = "place_categories")
data class PlaceCategoryEntity(
    @PrimaryKey val code: String,
    val titleUz: String,
    val titleRu: String,
    val sortOrder: Int,
)
