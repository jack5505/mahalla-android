package uz.mahalla.feature.freelancer.domain

import androidx.compose.runtime.Immutable

/**
 * Мастер-фрилансер (issue #107).
 *
 * Приезжает из `GET /api/v1/freelancers` и `GET /api/v1/freelancers/{id}` —
 * схема `ProfileResponse`. Имя в `/v3/api-docs` встречается один раз, коллизии
 * springdoc здесь нет, поэтому поля прочитаны как есть:
 * `id, userId, name, profession, bio, city, phone, hourlyRate,
 * experienceYears, isAvailable, ratingAvg, ratingCount`.
 *
 * **Мастер — не заведение.** У него свой каталог, свои услуги и свои заказы,
 * отдельно от `places`: `PlaceCategory.Master` (`BARBER`) — это про
 * парикмахерскую как точку на карте, а здесь человек, который приезжает сам.
 * Поэтому и модель своя, а не [uz.mahalla.feature.discovery.domain.Place].
 *
 * @param profession специальность. Именно её человек и ищет («сантехник»,
 * «электрик»), поэтому она показывается рядом с именем и уходит в фильтр
 * каталога.
 * @param hourlyRateSum ставка за час. Бэкенд отдаёт `hourlyRate` целым числом
 * **без** дробного близнеца (в кошельке пара `balance`/`balanceSom` есть —
 * issue #62, здесь нет), поэтому считаем сумами, как в «Еде» (issue #9), брони
 * (issue #97) и у врачей (issue #99). Ноль — «ставка не названа»: экран тогда
 * её просто не показывает, а не пишет «0 сум».
 * @param isAvailable мастер сейчас берёт заказы. Молчание сервера — «берёт»:
 * спрятать кнопку заказа из-за отсутствующего поля хуже, чем показать её и
 * получить честный отказ.
 * @param ratingAvg и [ratingCount] — единственное, что приложение знает об
 * отзывах о мастере: своей ручки отзывов у фрилансеров нет
 * (`reviews/places/{placeId}` — про заведения), см. риски issue #107.
 */
@Immutable
data class Freelancer(
    val id: String,
    val name: String,
    val profession: String? = null,
    val bio: String? = null,
    val city: String? = null,
    val phone: String? = null,
    val hourlyRateSum: Long = 0,
    val experienceYears: Int? = null,
    val isAvailable: Boolean = true,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
)

/**
 * Страница каталога мастеров.
 *
 * @param hasMore есть ли что догружать. Считается по `last`, а при его
 * отсутствии — по `page`/`totalPages`; полного молчания сервера о страницах
 * достаточно, чтобы остановиться (то же правило, что у уведомлений, issue #81,
 * у «моих заведений», issue #94, и у записей, issue #97): лучше не показать
 * хвост списка, чем зациклить догрузку одной и той же страницы.
 */
data class FreelancerPage(
    val items: List<Freelancer> = emptyList(),
    val hasMore: Boolean = false,
)
