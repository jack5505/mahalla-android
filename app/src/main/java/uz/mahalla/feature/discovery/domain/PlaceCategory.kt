package uz.mahalla.feature.discovery.domain

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.graphics.vector.ImageVector
import uz.mahalla.R

/**
 * Категории каталога (эпик 4.1). Набор фиксирован ТЗ: еда, аптеки, больницы,
 * кино, игровые зоны, мастера.
 *
 * [Other] — не «седьмая категория», а место для значений, которых ещё нет в
 * приложении: сервер может отдать новую категорию раньше релиза. Такое место
 * показывается в списке, но ни один фильтр по категории его не выбирает —
 * поэтому [Other] и не попадает в [selectable].
 */
enum class PlaceCategory(
    val apiValue: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Food("food", R.string.category_food, Icons.Outlined.Restaurant),
    Pharmacy("pharmacy", R.string.category_pharmacy, Icons.Outlined.LocalPharmacy),
    Hospital("hospital", R.string.category_hospital, Icons.Outlined.LocalHospital),
    Cinema("cinema", R.string.category_cinema, Icons.Outlined.Movie),
    Playground("playground", R.string.category_playground, Icons.Outlined.SportsEsports),
    Master("master", R.string.category_master, Icons.Outlined.Build),
    Other("", R.string.category_other, Icons.Outlined.Category),
    ;

    companion object {
        /** Категории, которые пользователь может выбрать в фильтрах и на главной. */
        val selectable: List<PlaceCategory> = entries.filter { it != Other }

        /** Неизвестное или пустое значение — [Other], а не исключение. */
        fun fromApi(value: String?): PlaceCategory =
            selectable.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Other

        /** `null` для [Other]: по нему нельзя фильтровать на сервере. */
        fun apiValueOrNull(category: PlaceCategory): String? =
            category.apiValue.takeIf { it.isNotEmpty() }
    }
}
