package uz.mahalla.feature.role.domain

/**
 * Сотрудник заведения (`place-staff-controller`, issue #189).
 *
 * Ключ записи — [userId], а не `id` записи из схемы `PlaceStaffResponse`:
 * обе ручки, которые адресуют конкретного сотрудника (`PUT`/`DELETE
 * .../staff/{staffUserId}`), принимают именно его, и отдельно хранить `id`
 * незачем — им всё равно нечего было бы делать.
 *
 * [role] — то же перечисление, что и у `Mine.role` в списке «мои заведения»
 * ([PlaceStaffRole]): `/v3/api-docs` объявляет его тем же набором значений
 * (`STAFF`, `MANAGER`, `OWNER`), заводить второй тип под тот же смысл незачем.
 */
data class PlaceStaffMember(
    val userId: String,
    val role: PlaceStaffRole,
    val geoExempt: Boolean = false,
)

/**
 * Грубая проверка формата UUID перед отправкой `POST places/{id}/staff`:
 * схема не даёт способа найти пользователя по телефону, поэтому ID вводится
 * вручную, и опечатку дешевле поймать на клиенте, чем ответом `400` после
 * запроса.
 */
internal fun isPlausibleUserId(value: String): Boolean = UUID_REGEX.matches(value)

private val UUID_REGEX = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)
