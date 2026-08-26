package uz.mahalla.feature.place.domain

import uz.mahalla.feature.discovery.domain.Place

/**
 * Набор действий карточки (эпик 4.4).
 *
 * Действие показывается только если его есть чем выполнить: «Позвонить» без
 * телефона и «Маршрут» без координат — кнопки, ведущие в никуда. Порядок
 * фиксирован макетом: сначала основное действие вертикали, потом связь.
 */
object PlaceActions {

    fun resolve(
        capabilities: PlaceCapabilities,
        contacts: PlaceContacts,
        place: Place,
    ): List<PlaceAction> = buildList {
        if (capabilities.queue) add(PlaceAction.Queue)
        if (capabilities.booking) add(PlaceAction.Booking)
        if (capabilities.ordering) add(PlaceAction.Order)
        if (!contacts.phone.isNullOrBlank()) add(PlaceAction.Call)
        if (place.point != null) add(PlaceAction.Route)
    }

    /**
     * Главное действие — первое из доступных. Оно рисуется primary-кнопкой,
     * остальные — вторичными.
     */
    fun primary(actions: List<PlaceAction>): PlaceAction? = actions.firstOrNull()
}
