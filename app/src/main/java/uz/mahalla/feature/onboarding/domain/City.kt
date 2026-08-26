package uz.mahalla.feature.onboarding.domain

/**
 * Города для ручного выбора, когда пользователь отказал в доступе к
 * геолокации (3.6). Без города каталог показывать нечего, поэтому отказ —
 * это не тупик, а альтернативный путь.
 *
 * Домен без Android: подписи берутся из ресурсов в UI-слое (как у
 * `AppLanguage`), чтобы список работал на обоих языках и тестировался на JVM.
 */
enum class City(val id: String) {
    TASHKENT("tashkent"),
    SAMARKAND("samarkand"),
    BUKHARA("bukhara"),
    ANDIJAN("andijan"),
    NAMANGAN("namangan"),
    FERGANA("fergana"),
    NUKUS("nukus"),
    QARSHI("qarshi"),
    ;

    companion object {
        /** Столица — самый частый выбор, поэтому и дефолт списка. */
        val Default: City = TASHKENT

        fun fromId(id: String?): City? = entries.firstOrNull { it.id == id }
    }
}
