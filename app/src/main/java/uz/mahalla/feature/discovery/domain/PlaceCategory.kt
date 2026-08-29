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
 * [apiValue] — значение перечисления бэкенда (issue #53, схема стенда:
 * `FOOD`, `PHARMACY`, `HOSPITAL`, `CINEMA`, `GAMING`, `BARBER`, …). Оно уходит
 * в параметр `category` и хранится в кэше Room. [aliases] — прочие написания,
 * которые могут приехать в ответе или лежать в кэше от прежних версий
 * приложения; разбор регистронезависимый.
 *
 * [Other] — не «седьмая категория», а место для значений, которых ещё нет в
 * приложении (`BAKERY`, `SHOP`, `MUSEUM`, `PARK`, `MOSQUE`, `FASHION`): сервер
 * может отдать новую категорию раньше релиза. Такое место показывается в
 * списке, но ни один фильтр по категории его не выбирает — поэтому [Other] и
 * не попадает в [selectable].
 */
enum class PlaceCategory(
    val apiValue: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    private val aliases: Set<String> = emptySet(),
) {
    Food("FOOD", R.string.category_food, Icons.Outlined.Restaurant, setOf("food")),
    Pharmacy("PHARMACY", R.string.category_pharmacy, Icons.Outlined.LocalPharmacy, setOf("pharmacy")),
    Hospital("HOSPITAL", R.string.category_hospital, Icons.Outlined.LocalHospital, setOf("hospital")),
    Cinema("CINEMA", R.string.category_cinema, Icons.Outlined.Movie, setOf("cinema")),
    Playground(
        "GAMING",
        R.string.category_playground,
        Icons.Outlined.SportsEsports,
        setOf("gaming", "playground"),
    ),
    Master(
        "BARBER",
        R.string.category_master,
        Icons.Outlined.Build,
        // Фрилансер из каталога бэкенда — тот же «мастер» из ТЗ.
        setOf("barber", "master", "freelancer"),
    ),
    Other("", R.string.category_other, Icons.Outlined.Category),
    ;

    private fun matches(value: String): Boolean =
        apiValue.equals(value, ignoreCase = true) ||
            aliases.any { it.equals(value, ignoreCase = true) }

    companion object {
        /** Категории, которые пользователь может выбрать в фильтрах и на главной. */
        val selectable: List<PlaceCategory> = entries.filter { it != Other }

        /** Неизвестное или пустое значение — [Other], а не исключение. */
        fun fromApi(value: String?): PlaceCategory {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return Other
            return selectable.firstOrNull { it.matches(raw) } ?: Other
        }

        /** `null` для [Other]: по нему нельзя фильтровать на сервере. */
        fun apiValueOrNull(category: PlaceCategory): String? =
            category.apiValue.takeIf { it.isNotEmpty() }
    }
}
