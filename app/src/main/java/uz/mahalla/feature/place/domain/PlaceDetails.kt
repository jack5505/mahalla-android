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

/**
 * @param city отдельно от [address]: тот при пустом адресе показывает город
 * вместо него (см. `PlaceDetailDto.toDetails`), а форма правки места (issue
 * #188) должна отправить обратно настоящее значение поля, а не подмену для
 * витрины.
 */
@Immutable
data class PlaceContacts(
    val phone: String? = null,
    val website: String? = null,
    val address: String? = null,
    val city: String? = null,
)

/** Что можно сделать в этом месте (эпик 4.4, кнопки действий). */
enum class PlaceAction {
    Queue,
    Booking,

    /**
     * Игровая зона клуба (issue #98). Отдельно от [Booking]: там запись на
     * время к мастеру (`barber-services` + `appointments`), здесь зона клуба
     * с почасовой ценой (`gaming-controller`) — другой список и другой экран.
     */
    Gaming,

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

    /**
     * Витрина магазина одежды (issue #108). Отдельно от [Order]: у «Еды» это
     * меню заведения с корзиной в Room, а здесь каталог товаров с вариантами и
     * корзиной на сервере — общего у них только слово «заказать».
     */
    Shop,

    /**
     * Витрина товаров аптеки (issue #100). Это единственное действие, которое
     * ничего не начинает: заказать товар нечем, поэтому и называется оно
     * «Товары», а не «Купить».
     */
    Products,
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
    /** Игровая зона — вертикаль «Игровые зоны» (issue #98). */
    val gaming: Boolean = false,
    /** Запись к врачу — вертикаль больниц (issue #99). */
    val doctors: Boolean = false,
    /** Билет в кино — вертикаль кинотеатров (issue #106). */
    val cinema: Boolean = false,
    val ordering: Boolean = false,
    /** Витрина магазина одежды — вертикаль «Одежда» (issue #108). */
    val shopping: Boolean = false,
    /** Витрина товаров аптеки — вертикаль «Аптека» (issue #100). */
    val products: Boolean = false,
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
         * У игровых клубов (`GAMING`) это зона (`gaming-controller`, issue
         * #98): свой контроллер, своя почасовая цена и свой экран. Зон в
         * клубе может и не оказаться — тогда экран скажет это словами;
         * спрятать кнопку заранее нельзя, список зон известен только серверу.
         *
         * У больниц (`HOSPITAL`) это запись к врачу (`hospital-controller`,
         * issue #99): список врачей известен только серверу, поэтому кнопка
         * показывается всегда, а «врачей пока нет» экран скажет словами.
         *
         * У кинотеатров (`CINEMA`) это билет (`cinema-controller`, issue
         * #106): афиша и расписание тоже известны только серверу — «афиша
         * пуста» экран скажет словами.
         *
         * У магазинов одежды (`FASHION`) это витрина (`fashion-controller`,
         * issue #108): каталог известен только серверу, поэтому кнопка
         * показывается всегда, а «товаров пока нет» экран скажет словами.
         *
         * У аптек (`PHARMACY`) это витрина товаров (issue #100) — действие,
         * которое ничего не начинает: заказа у аптеки бэкенд не принимает, и
         * кнопки «купить» здесь нет. Товаров в аптеке может и не оказаться —
         * тогда экран скажет это словами; спрятать кнопку заранее нельзя,
         * витрина известна только серверу.
         *
         * [ordering] остаётся выключенным: «Заказать» — это вертикаль «Еда»,
         * её экраны есть, но включение кнопки в объём этих задач не входило.
         */
        fun of(category: PlaceCategory): PlaceCapabilities = when (category) {
            PlaceCategory.Master -> PlaceCapabilities(queue = true, booking = true)
            PlaceCategory.Playground -> PlaceCapabilities(gaming = true)
            PlaceCategory.Hospital -> PlaceCapabilities(doctors = true)
            PlaceCategory.Cinema -> PlaceCapabilities(cinema = true)
            PlaceCategory.Fashion -> PlaceCapabilities(shopping = true)
            PlaceCategory.Pharmacy -> PlaceCapabilities(products = true)
            else -> PlaceCapabilities()
        }
    }
}

/**
 * @param authorId id автора с сервера. Единственный признак, по которому свой
 * отзыв отличается от чужого (issue #76) — «мой» это факт про аккаунт, а не
 * про отзыв, поэтому сравнение живёт в состоянии экрана, а не здесь.
 * @param ownerReply ответ владельца заведения (issue #188). `null` — ответа
 * нет, а не «нет отзыва на ответ»: это два разных отсутствия.
 */
@Immutable
data class Review(
    val id: String,
    val author: String,
    val rating: Int,
    val text: String,
    val createdAt: Instant?,
    val authorId: String? = null,
    /** Аватар автора (issue #60); `null` — рисуется первая буква имени. */
    val avatarUrl: String? = null,
    val ownerReply: String? = null,
)

/**
 * Полная карточка места. [place] — та же модель, что в выдаче: карточка
 * открывается из списка, и заголовок не должен «дёргаться» из-за другого
 * источника имени и рейтинга.
 *
 * [fromCache] отмечает данные, поднятые из Room после сетевой ошибки: экран
 * показывает их, но подписывает — иначе устаревшие часы работы выглядят как
 * актуальные.
 *
 * @param ownerId владелец заведения (issue #188). Кэш его не хранит — из
 * офлайн-карточки владельческие действия не предлагаются: подтвердить
 * личность без сети нечем.
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
    val ownerId: String? = null,
) {
    val actions: List<PlaceAction> get() = PlaceActions.resolve(capabilities, contacts, place)
}
