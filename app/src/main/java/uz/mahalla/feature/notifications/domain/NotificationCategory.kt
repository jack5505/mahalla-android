package uz.mahalla.feature.notifications.domain

/**
 * Категория уведомления — она же канал Android (эпик 11).
 *
 * Категория, а не тип: типов у бэкенда тринадцать и список открытый, а каналов
 * в системных настройках должно быть столько, сколько человек готов
 * разглядывать. Каждый канал он выключает отдельно — и в системе, и на экране
 * настроек приложения.
 *
 * [Other] обязателен по той же причине, что и `NotificationType.Unknown`:
 * бэкенд заводит новые типы раньше, чем приложение о них узнаёт, и уведомление
 * незнакомого типа должно доехать хоть куда-то. Без него оно исчезло бы молча,
 * а «пуш не пришёл» — самая дорогая для разбора жалоба.
 *
 * @param id идентификатор канала в системе. Меняться он не может: после смены
 * Android заведёт новый канал со значениями по умолчанию, и всё, что человек
 * выключил, включится обратно.
 */
enum class NotificationCategory(val id: String) {

    /** Заказы еды, одежды и услуг мастеров: приняли, готовят, везут. */
    Orders("orders"),

    /** Электронная очередь: приняли талон, подошла очередь, отказали. */
    Queue("queue"),

    /** Записи на время — к мастеру и к врачу, включая напоминания. */
    Bookings("bookings"),

    /** Деньги: подписка заканчивается, списание, пополнение кошелька. */
    Payments("payments"),

    /** Акции и предложения — единственная категория, которую человек чаще
     *  всего и выключает. */
    Marketing("marketing"),

    /** Всё остальное, включая типы, о которых приложение ещё не знает. */
    Other("other"),
    ;

    companion object {

        /**
         * Чистая функция: маппинг проверяется без Android и без Firebase.
         *
         * `REVIEW_ADDED` попадает в [Other] намеренно: отзыв — это ни заказ, ни
         * очередь, ни деньги, а заводить ради него шестую видимую категорию
         * значит просить человека принять решение о том, чего он не просил.
         */
        fun of(type: NotificationType): NotificationCategory = when (type) {
            NotificationType.OrderPlaced,
            NotificationType.OrderStatusUpdated,
            -> Orders

            NotificationType.WalkinRequest,
            NotificationType.WalkinAccepted,
            NotificationType.WalkinDeclined,
            NotificationType.WalkinCounter,
            NotificationType.WalkinComplete,
            -> Queue

            NotificationType.AppointmentBooked,
            NotificationType.AppointmentConfirmed,
            NotificationType.AppointmentReminder,
            -> Bookings

            NotificationType.SubscriptionExpires -> Payments

            NotificationType.PromotionCreated -> Marketing

            NotificationType.ReviewAdded,
            NotificationType.Unknown,
            -> Other
        }
    }
}
