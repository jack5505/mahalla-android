package uz.mahalla.feature.role.domain

import uz.mahalla.feature.discovery.domain.PlaceCategory

/**
 * Заведение, которым человек владеет или в котором работает (issue #94).
 *
 * Приезжает из `GET /api/v1/places/my` — схема `Mine`. В отличие от
 * `CreateRequest` (issue #84) это имя в `/v3/api-docs` **не перекрыто**
 * коллизией springdoc: оно встречается ровно один раз, поэтому поля взяты из
 * схемы как есть, а не выведены из ответа соседнего эндпоинта.
 *
 * Главное здесь — [status]: заявка, отправленная из анкеты продавца, уходит
 * `PENDING`, и до issue #94 её судьбу в приложении было не видно вовсе.
 *
 * @param isAvailable «открыто сейчас» — единственный признак работы
 * заведения, который отдаёт бэкенд (расписания в контракте нет, issue #53).
 * @param staffRole кем человек числится в заведении. Список `places/my`
 * включает и те места, где он не владелец, а сотрудник, — и действия у них
 * разные.
 */
data class MyPlace(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val status: PlaceModerationStatus,
    val address: String? = null,
    val isAvailable: Boolean = false,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val staffRole: PlaceStaffRole = PlaceStaffRole.Unknown,
) {

    /**
     * Карточка заведения в каталоге (`GET places/{id}`) есть только у того,
     * что модерация уже пропустила: заявка `PENDING` в каталог не попадает, и
     * переход на неё кончился бы «заведение не найдено». Нажатие, которое
     * ведёт в ошибку, хуже строки, которая не нажимается.
     */
    val isOpenable: Boolean get() = status == PlaceModerationStatus.Active

    /**
     * Показывать ли переключатель «открыто сейчас».
     *
     * Два условия. Первое: заведение прошло модерацию — закрывать на обед то,
     * чего в каталоге ещё нет, незачем. Второе: человек не рядовой сотрудник;
     * `PUT places/{id}/availability` — действие владельца, и переключатель,
     * который каждый раз отвечает отказом, читается как сломанный.
     *
     * [PlaceStaffRole.Unknown] сюда входит намеренно: все поля `Mine`
     * необязательны, и молчание сервера о роли не повод спрятать
     * переключатель от владельца.
     */
    val canToggleAvailability: Boolean
        get() = status == PlaceModerationStatus.Active && staffRole != PlaceStaffRole.Staff
}

/**
 * Роль человека в заведении (`Mine.role`).
 *
 * [Unknown] — не ошибка: поле необязательное, а новую роль бэкенд может
 * завести раньше релиза приложения.
 */
enum class PlaceStaffRole(val apiValue: String) {
    Owner("OWNER"),
    Manager("MANAGER"),
    Staff("STAFF"),
    Unknown(""),
    ;

    companion object {
        fun fromApi(value: String?): PlaceStaffRole {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue.equals(raw, ignoreCase = true) } ?: Unknown
        }
    }
}

/**
 * Страница списка «мои заведения».
 *
 * @param hasMore есть ли что догружать. Считается по `last`, а при его
 * отсутствии — по `page`/`totalPages`; полного молчания сервера о страницах
 * достаточно, чтобы остановиться (то же правило, что у уведомлений, issue
 * #81, и у истории кошелька, issue #62): лучше не показать хвост списка, чем
 * зациклить догрузку одной и той же страницы.
 */
data class MyPlacePage(
    val items: List<MyPlace> = emptyList(),
    val hasMore: Boolean = false,
)
