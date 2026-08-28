package uz.mahalla.feature.onboarding.domain

/**
 * Города для ручного выбора, когда пользователь отказал в доступе к
 * геолокации (3.6). Без города каталог показывать нечего, поэтому отказ —
 * это не тупик, а альтернативный путь.
 *
 * Домен без Android: подписи берутся из ресурсов в UI-слое (как у
 * `AppLanguage`), чтобы список работал на обоих языках и тестировался на JVM.
 */
enum class City(
    val id: String,
    /**
     * Центр города. Нужен запросам авторизации: бэкенд требует координаты, а
     * разрешение на геолокацию к этому моменту обычно ещё не выдано
     * (см. `RequestLocationProvider`).
     */
    val latitude: Double,
    val longitude: Double,
) {
    TASHKENT("tashkent", latitude = 41.311081, longitude = 69.240562),
    SAMARKAND("samarkand", latitude = 39.627012, longitude = 66.974977),
    BUKHARA("bukhara", latitude = 39.767070, longitude = 64.421986),
    ANDIJAN("andijan", latitude = 40.783380, longitude = 72.350403),
    NAMANGAN("namangan", latitude = 40.998244, longitude = 71.671574),
    FERGANA("fergana", latitude = 40.372480, longitude = 71.787770),
    NUKUS("nukus", latitude = 42.460236, longitude = 59.617130),
    QARSHI("qarshi", latitude = 38.863134, longitude = 65.789948),
    ;

    companion object {
        /** Столица — самый частый выбор, поэтому и дефолт списка. */
        val Default: City = TASHKENT

        fun fromId(id: String?): City? = entries.firstOrNull { it.id == id }
    }
}
