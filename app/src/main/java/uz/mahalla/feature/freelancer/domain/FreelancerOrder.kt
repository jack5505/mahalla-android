package uz.mahalla.feature.freelancer.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.feature.booking.domain.BookingSlots
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Состояние заказа у мастера (issue #107).
 *
 * Значения — перечисление бэкенда из схемы `OrderResponse`
 * (`PENDING`, `ACCEPTED`, `REJECTED`, `COMPLETED`), снятой со стенда
 * 2026-09-04. Оно короче, чем у записи на время: промежуточных «в работе» и
 * «не пришёл» у заказа мастера нет.
 *
 * [Unknown] обязателен: состояние меняет сам мастер
 * (`PUT freelancers/orders/{orderId}/status`, его кабинет — эпик #16), и новое
 * значение не должно прятать заказ из списка.
 */
enum class FreelancerOrderStatus(val apiValue: String) {
    /** Заказ создан, мастер ещё не ответил. */
    Pending("PENDING"),

    /** Мастер взялся. */
    Accepted("ACCEPTED"),

    Rejected("REJECTED"),
    Completed("COMPLETED"),

    Unknown(""),
    ;

    /**
     * Дальше состояние не изменится. [Unknown] финальным **не** считается:
     * незнакомое значение — это «неизвестно, чем кончилось», и объявлять заказ
     * закрытым по нему нельзя (то же правило, что у талона очереди, issue #96,
     * и у записи, issue #97).
     */
    val isFinal: Boolean get() = this == Rejected || this == Completed

    companion object {
        fun fromApi(value: String?): FreelancerOrderStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Заказ услуги у мастера.
 *
 * @param id идентификатор с сервера. **Может быть пустым**, и только в одном
 * случае — сразу после создания, если ответ `POST freelancers/{id}/orders`
 * пришёл без `id`. Это не отказ: заказ создан, и увидеть его всё равно можно в
 * «моих заказах» (`GET freelancers/orders/my`), в отличие от талона очереди,
 * где читать состояние нечем и ответ без `id` приходится считать негодным
 * (issue #96). В самом списке заказ без `id` отбрасывается — в `LazyColumn`
 * это дубликат ключа.
 * @param scheduledAt желаемое время. Необязательно и в контракте
 * (`CreateOrderRequest` требует только `serviceId`), и на экране: «как можно
 * скорее» — обычный способ вызвать мастера.
 * @param priceSum цена услуги на момент заказа; ноль — «не названа».
 */
@Immutable
data class FreelancerOrder(
    val id: String,
    val freelancerId: String? = null,
    val serviceId: String? = null,
    val serviceTitle: String? = null,
    val priceSum: Long = 0,
    val status: FreelancerOrderStatus = FreelancerOrderStatus.Unknown,
    val scheduledAt: Instant? = null,
    val address: String? = null,
    val comment: String? = null,
    val createdAt: Instant? = null,
)

/** Страница «моих заказов у мастеров»; правило [hasMore] — как у каталога. */
data class FreelancerOrderPage(
    val items: List<FreelancerOrder> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Черновик заказа: что человек выбрал до отправки.
 *
 * Правилами это отдельный класс, а не поля состояния экрана, по той же
 * причине, что [uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft]
 * (issue #99) и `ReviewDraft` (issue #76): форму нельзя проверить ни
 * скриншотом, ни запросом, а «кнопка включилась раньше времени» стоит человеку
 * отказа сервера вместо подсказки на экране.
 *
 * Обязателен здесь **только** `serviceId` — так же, как в контракте
 * (`CreateOrderRequest.required = [serviceId]`). Адрес и комментарий
 * необязательны, а время — тем более: мастера часто вызывают «как можно
 * скорее», и требовать от человека выбрать час значило бы придумать за бэкенд
 * правило, которого у него нет.
 *
 * @param date день заказа. Выбран всегда (по умолчанию сегодня) — календарь без
 * выбранного дня не отвечает на вопрос, чьё время показано ниже. Само по себе
 * это ещё не «назначено на время»: назначает [time].
 * @param time час. `null` — «как можно скорее», и тогда `scheduledAt` в запрос
 * не уходит вовсе.
 */
@Immutable
data class FreelancerOrderDraft(
    val serviceId: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val address: String = "",
    val comment: String = "",
) {

    /** Пробелы по краям ни длиной, ни содержанием не считаем. */
    val trimmedAddress: String get() = address.trim()

    val trimmedComment: String get() = comment.trim()

    /**
     * Ограничения бэкенда — `@Size(max = 500)` у адреса и `@Size(max = 1000)` у
     * комментария. Резать текст на вводе нельзя: человек не поймёт, куда
     * пропали набранные символы, — поэтому лишнее показывается ошибкой, а
     * отправка блокируется.
     */
    val isAddressTooLong: Boolean get() = trimmedAddress.length > MAX_ADDRESS_LENGTH

    val isCommentTooLong: Boolean get() = trimmedComment.length > MAX_COMMENT_LENGTH

    val canSubmit: Boolean
        get() = !serviceId.isNullOrBlank() && !isAddressTooLong && !isCommentTooLong

    /**
     * Момент, на который человек просит мастера. `null` — «как можно скорее»:
     * без выбранного часа день сам по себе времени не назначает.
     */
    fun scheduledAt(zone: ZoneId = DateTimeFormatters.AppZone): Instant? {
        val day = date ?: return null
        val hour = time ?: return null
        return BookingSlots.startsAt(day, hour, zone)
    }

    /** Пустые поля уходят отсутствующими, а не пустыми строками. */
    fun addressOrNull(): String? = trimmedAddress.takeIf(String::isNotBlank)

    fun commentOrNull(): String? = trimmedComment.takeIf(String::isNotBlank)

    companion object {
        const val MAX_ADDRESS_LENGTH = 500
        const val MAX_COMMENT_LENGTH = 1000
    }
}
