package uz.mahalla.feature.notifications.push

import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings
import java.time.LocalTime

/**
 * Состояние системных разрешений на момент прихода пуша (эпик 11).
 *
 * Отдельный тип, а не три булевых аргумента подряд: перепутанные местами
 * `permissionGranted` и `channelEnabled` компилировались бы молча, а разница
 * между ними — это разница между «пуш не показали» и «пуш показали не в том
 * канале».
 *
 * @param permissionGranted `POST_NOTIFICATIONS` выдано (API 33+). Ниже API 33
 * разрешения не существует — там всегда `true`.
 * @param notificationsEnabled уведомления приложения не выключены целиком в
 * системных настройках. Выключить их можно на любой версии Android.
 * @param channelEnabled канал этой категории не выключен пользователем в
 * системных настройках.
 */
data class PushPermissions(
    val permissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
)

/** Почему пуш не показан. Значение для разбора, а не для интерфейса. */
enum class PushSuppression {
    /** Разрешение `POST_NOTIFICATIONS` не выдано или отозвано. */
    PermissionRevoked,

    /** Уведомления приложения выключены в системных настройках целиком. */
    NotificationsDisabled,

    /** Канал этой категории выключен в системных настройках. */
    ChannelDisabled,

    /** Категория выключена на экране настроек уведомлений приложения. */
    CategoryMuted,
}

/** Что делать с пришедшим пушем. */
sealed interface PushDecision {

    /**
     * Показать.
     *
     * @param silent тихие часы: уведомление показывается, но без звука и
     * вибрации. Именно показывается — выбрасывать его было бы хуже: утром
     * человек не узнал бы, что ночью его заказ отменили, а «тихие часы» он
     * включал ради сна, а не ради потери уведомлений.
     */
    data class Show(
        val category: NotificationCategory,
        val silent: Boolean,
    ) : PushDecision

    data class Suppressed(val reason: PushSuppression) : PushDecision
}

/**
 * Решение «показывать ли пуш» — чистая функция (эпик 11).
 *
 * Вынесено из [PushNotifier] намеренно: проверить отозванное разрешение,
 * выключенную категорию и границу тихих часов на настоящем
 * `NotificationManager` нельзя — эмулятора в CI нет (AGENTS.md), а здесь
 * достаточно обычного JVM-теста.
 */
object PushGate {

    fun decide(
        message: PushMessage,
        settings: NotificationSettings,
        permissions: PushPermissions,
        now: LocalTime,
    ): PushDecision {
        val category = message.category
        // Порядок важен: сперва то, что решил пользователь в системе, потом
        // то, что он решил в приложении. Отозванное разрешение — единственная
        // причина, по которой показать нельзя физически.
        return when {
            !permissions.permissionGranted ->
                PushDecision.Suppressed(PushSuppression.PermissionRevoked)

            !permissions.notificationsEnabled ->
                PushDecision.Suppressed(PushSuppression.NotificationsDisabled)

            !permissions.channelEnabled ->
                PushDecision.Suppressed(PushSuppression.ChannelDisabled)

            !settings.isEnabled(category) ->
                PushDecision.Suppressed(PushSuppression.CategoryMuted)

            else -> PushDecision.Show(
                category = category,
                silent = settings.quietHours.isQuiet(now),
            )
        }
    }
}
