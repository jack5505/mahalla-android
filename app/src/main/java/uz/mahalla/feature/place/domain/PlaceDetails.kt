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

    /**
     * Запись к врачу (issue #99). Отдельно от [Booking]: у больниц другой
     * список (врачи, а не услуги) и другой экран — общее действие пришлось бы
     * ветвить по категории уже в навигации.
     */
    Doctor,

    /**
     * Билет в кино (issue #106). Тоже отдельно: у кинотеатра сначала афиша, а
     * «забронировать» ведёт к услугам мастера, которых у него нет.
     */
    Cinema,
    Order,
    Call,
    Route,
}

/**
 * Что можно сделать в этом месте.
 *
 * Флагов `hasQueue`/`hasBooking`/`hasOrdering` в контракте нет вовсе (issue
 * #53) — у каждой вертикали свой контроллер, и что место умеет, следует из его
 * категории. До issue #96 это было записано комментарием в `PlaceMappers`, но
 * не сделано: все три флага оставались `false`, то есть ни одна вертикаль с
 * карточки места не открывалась.
 */
@Immutable
data class PlaceCapabilities(
    val queue: Boolean = false,
    val booking: Boolean = false,
    /** Запись к врачу — вертикаль больниц (issue #99). */
    val doctors: Boolean = false,
    /** Билет в кино — вертикаль кинотеатров (issue #106). */
    val cinema: Boolean = false,
    val ordering: Boolean = false,
) {
    companion object {
        /**
         * Действие включается только там, где его есть чем выполнить.
         *
         * У мастеров (`BARBER`) это оба способа попасть к мастеру, и они
         * дополняют друг друга: очередь — прийти сейчас (walk-in-контроллер,
         * issue #96), бронь — занять время заранее (`barber-services` +
         * `appointments`, issue #97). Услуг у заведения может и не быть — тогда
         * экран записи скажет это словами; спрятать кнопку заранее нельзя,
         * список услуг известен только серверу.
         *
         * У больниц (`HOSPITAL`) это запись к врачу (`hospital-controller`,
         * issue #99): список врачей известен только серверу, поэтому кнопка
         * показывается всегда, а «врачей пока нет» экран скажет словами.
         *
         * У кинотеатров (`CINEMA`) это билет (`cinema-controller`, issue
         * #106): афиша и расписание тоже известны только серверу — «афиша
         * пуста» экран скажет словами.
         *
         * [ordering] остаётся выключенным: «Заказать» — это вертикаль «Еда»,
         * её экраны есть, но включение кнопки в объём этих задач не входило.
         */
        fun of(category: PlaceCategory): PlaceCapabilities = when (category) {
            PlaceCategory.Master -> PlaceCapabilities(queue = true, booking = true)
            PlaceCategory.Hospital -> PlaceCapabilities(doctors = true)
            PlaceCategory.Cinema -> PlaceCapabilities(cinema = true)
            else -> PlaceCapabilities()
        }
    }
}

/**
 * @param authorId id автора с сервера. Единственный признак, по которому свой
 * отзыв отличается от чужого (issue #76) — «мой» это факт про аккаунт, а не
 * про отзыв, поэтому сравнение живёт в состоянии экрана, а не здесь.
 */
@Immutable
data class Review(
    val id: String,
    val author: String,
    val rating: Int,
    val text: String,
    val createdAt: Instant?,
    val authorId: String? = null,
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
