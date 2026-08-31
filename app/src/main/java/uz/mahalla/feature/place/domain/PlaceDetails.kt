package uz.mahalla.feature.place.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Часы работы одного дня (эпик 4.4).
 *
 * `opensAt == closesAt` трактуется как круглосуточно: так этот случай приходит
 * с сервера (`00:00–00:00`), и отдельного флага в контракте нет.
 */
@Immutable
data class OpeningHours(
    val dayOfWeek: DayOfWeek,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
) {
    val isDayOff: Boolean get() = opensAt == null || closesAt == null

    val isAroundTheClock: Boolean get() = !isDayOff && opensAt == closesAt

    /** Интервал переходит через полночь: закрытие «раньше» открытия. */
    val isOvernight: Boolean
        get() = !isDayOff && !isAroundTheClock && closesAt!! < opensAt!!
}

@Immutable
data class PlaceContacts(
    val phone: String? = null,
    val website: String? = null,
    val address: String? = null,
)

/** Что можно сделать в этом месте (эпик 4.4, кнопки действий). */
enum class PlaceAction {
    Queue,
    Booking,
    Order,
    Call,
    Route,
}

/**
 * Что место умеет.
 *
 * Раньше это приходило с сервера (`hasQueue`/`hasBooking`/`hasOrdering`), но в
 * реальном контракте таких полей нет (issue #53), и с тех пор набор оставался
 * пустым: **ни одна кнопка вертикали на карточке не показывалась**. Теперь он
 * выводится из категории — у каждой вертикали свой контроллер бэкенда
 * (`walkin/send`, `food/…/menu`, `gaming/…/zones`).
 */
@Immutable
data class PlaceCapabilities(
    val queue: Boolean = false,
    val booking: Boolean = false,
    val ordering: Boolean = false,
) {
    companion object {

        /**
         * Действие включается, только когда его есть чем выполнить: кнопка,
         * ведущая на несуществующий экран, хуже её отсутствия.
         *
         * - [PlaceCategory.Master] — очередь: форма заказа услуги (issue #71),
         *   `POST walkin/send`.
         * - Еда, игровые зоны, кино, больницы: экраны есть не у всех, а у еды
         *   пути `FoodApi` расходятся с бэкендом (`docs/UI-INVENTORY.md` §3.1)
         *   — заказ включится вместе с их починкой.
         */
        fun forCategory(category: PlaceCategory): PlaceCapabilities = when (category) {
            PlaceCategory.Master -> PlaceCapabilities(queue = true)
            else -> PlaceCapabilities()
        }
    }
}

@Immutable
data class Review(
    val id: String,
    val author: String,
    val rating: Int,
    val text: String,
    val createdAt: Instant?,
)

/**
 * Полная карточка места. [place] — та же модель, что в выдаче: карточка
 * открывается из списка, и заголовок не должен «дёргаться» из-за другого
 * источника имени и рейтинга.
 *
 * [fromCache] отмечает данные, поднятые из Room после сетевой ошибки: экран
 * показывает их, но подписывает — иначе устаревшие часы работы выглядят как
 * актуальные.
 */
@Immutable
data class PlaceDetails(
    val place: Place,
    val description: String? = null,
    val photos: List<String> = emptyList(),
    val hours: List<OpeningHours> = emptyList(),
    val contacts: PlaceContacts = PlaceContacts(),
    val capabilities: PlaceCapabilities = PlaceCapabilities(),
    val reviews: List<Review> = emptyList(),
    val fromCache: Boolean = false,
) {
    val actions: List<PlaceAction> get() = PlaceActions.resolve(capabilities, contacts, place)
}
