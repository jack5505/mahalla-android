package uz.mahalla.feature.onboarding.ui

import androidx.annotation.StringRes
import uz.mahalla.R
import uz.mahalla.feature.onboarding.domain.City

/**
 * Подпись города. Домен `City` про Android не знает (он тестируется на JVM),
 * поэтому названия живут в ресурсах, а сопоставление — здесь: один список на
 * все экраны, где город выбирают (шаг геолокации и анкеты из issue #84).
 */
@StringRes
fun City.labelRes(): Int = when (this) {
    City.TASHKENT -> R.string.city_tashkent
    City.SAMARKAND -> R.string.city_samarkand
    City.BUKHARA -> R.string.city_bukhara
    City.ANDIJAN -> R.string.city_andijan
    City.NAMANGAN -> R.string.city_namangan
    City.FERGANA -> R.string.city_fergana
    City.NUKUS -> R.string.city_nukus
    City.QARSHI -> R.string.city_qarshi
}
