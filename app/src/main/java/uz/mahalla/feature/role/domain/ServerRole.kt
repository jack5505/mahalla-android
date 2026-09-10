package uz.mahalla.feature.role.domain

/**
 * Роль пользователя в правах бэкенда (`MeResponse.role`, `UserInfo.role`) —
 * issue #237.
 *
 * Это **не** [UserRole]. Одно слово «роль» стоит в проекте на двух разных
 * вещах, и путать их дорого:
 *
 * - [ServerRole] — права на сервере. Значений четырнадцать, меняет их только
 *   админ (`PUT api/v1/admin/users/{id}/role`); приложению отправить свою
 *   роль нечем — ни `PUT users/me`, ни любое другое тело запроса поля `role`
 *   не принимают. Значит по правам главный сервер, а локальная копия — кэш
 *   ответа на вход, как имя и аватар.
 * - [UserRole] — какую анкету человек заполняет (покупатель или продавец).
 *   Значений два, выбирает их сам человек, на сервер они не уходят вовсе.
 *
 * [Unknown] — роль, которой приложение ещё не знает: бэкенд заведёт новую, и
 * профиль обязан открыться, а не упасть на разборе.
 */
enum class ServerRole {
    User,
    Barber,
    Baker,
    ShopOwner,
    FoodOwner,
    GamingOwner,
    MuseumOwner,
    ParkOwner,
    MosqueOwner,
    PharmacyOwner,
    HospitalOwner,
    CinemaOwner,
    Freelancer,
    Admin,
    Unknown,
    ;

    /**
     * Человек оказывает услуги: у него есть заведение, кабинет мастера или и
     * то и другое.
     *
     * По этому признаку показываются «Мои заведения»: до issue #237 строка
     * зависела только от локального переключателя анкеты, и настоящий
     * владелец кафе, который анкету не заполнял, своего заведения в
     * приложении не находил.
     *
     * [Freelancer] здесь тоже «да», хотя заведения у мастера может и не быть:
     * пустой список — цена ошибки в одну сторону, спрятанное заведение — в
     * другую, и вторая дороже. [Admin] — нет: администратору приложение
     * ничего не показывает, админка живёт отдельно. [Unknown] — нет: гадать о
     * правах роли, которой ещё не знаем, хуже, чем не показать строку.
     */
    val isProvider: Boolean
        get() = this != User && this != Admin && this != Unknown

    companion object {
        /**
         * Разбор значения из ответа сервера. `null` и пустая строка — тоже
         * [Unknown]: до первого входа роли просто нет.
         */
        fun fromServer(value: String?): ServerRole = when (value?.trim()?.uppercase()) {
            "USER" -> User
            "BARBER" -> Barber
            "BAKER" -> Baker
            "SHOP_OWNER" -> ShopOwner
            "FOOD_OWNER" -> FoodOwner
            "GAMING_OWNER" -> GamingOwner
            "MUSEUM_OWNER" -> MuseumOwner
            "PARK_OWNER" -> ParkOwner
            "MOSQUE_OWNER" -> MosqueOwner
            "PHARMACY_OWNER" -> PharmacyOwner
            "HOSPITAL_OWNER" -> HospitalOwner
            "CINEMA_OWNER" -> CinemaOwner
            "FREELANCER" -> Freelancer
            "ADMIN" -> Admin
            else -> Unknown
        }
    }
}
