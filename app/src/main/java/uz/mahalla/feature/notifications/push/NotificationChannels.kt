package uz.mahalla.feature.notifications.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import uz.mahalla.R
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.notifications.domain.NotificationCategory
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Название и описание канала в системных настройках. */
@StringRes
fun NotificationCategory.titleRes(): Int = when (this) {
    NotificationCategory.Orders -> R.string.notification_channel_orders
    NotificationCategory.Queue -> R.string.notification_channel_queue
    NotificationCategory.Bookings -> R.string.notification_channel_bookings
    NotificationCategory.Payments -> R.string.notification_channel_payments
    NotificationCategory.Marketing -> R.string.notification_channel_marketing
    NotificationCategory.Other -> R.string.notification_channel_other
}

@StringRes
fun NotificationCategory.descriptionRes(): Int = when (this) {
    NotificationCategory.Orders -> R.string.notification_channel_orders_description
    NotificationCategory.Queue -> R.string.notification_channel_queue_description
    NotificationCategory.Bookings -> R.string.notification_channel_bookings_description
    NotificationCategory.Payments -> R.string.notification_channel_payments_description
    NotificationCategory.Marketing -> R.string.notification_channel_marketing_description
    NotificationCategory.Other -> R.string.notification_channel_other_description
}

/**
 * Каналы уведомлений (эпик 11).
 *
 * minSdk 26, то есть каналы обязательны всегда: уведомление без
 * зарегистрированного канала система молча выбрасывает, и разбираться в этом
 * приходится по отсутствию, а не по ошибке.
 *
 * [ensureAll] зовётся на старте приложения (`RootViewModel`), и это не
 * перестраховка: сообщение с блоком `notification` в фоне показывает сама
 * библиотека Firebase, минуя [MahallaMessagingService], — она спрашивает канал
 * `other` из манифеста, и если его ещё нет, уведомление уезжает в «Разное»,
 * заведённое библиотекой. То есть до первого пуша каналы уже должны
 * существовать.
 *
 * `createNotificationChannel` идемпотентен: повторный вызов обновляет название
 * и описание (это нужно при смене языка) и **не возвращает** важность и звук —
 * после создания они принадлежат пользователю, а не приложению.
 *
 * Важность различается по категориям: заказ и очередь — то, ради чего человек
 * держит телефон в руке, акции — нет.
 */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
) {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * Завести все каналы разом. Одним вызовом, а не шестью: каждый — поход
     * через binder в системный сервис, а зовётся это со старта приложения.
     */
    suspend fun ensureAll() {
        val localized = localizedContext()
        manager.createNotificationChannels(
            NotificationCategory.entries.map { it.toChannel(localized) },
        )
    }

    suspend fun ensure(category: NotificationCategory) {
        manager.createNotificationChannel(category.toChannel(localizedContext()))
    }

    /**
     * Не выключен ли канал пользователем. Канала ещё нет — считаем включённым:
     * его заведёт [ensure] прямо перед показом.
     */
    fun isEnabled(category: NotificationCategory): Boolean {
        val channel = manager.getNotificationChannel(category.id) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Выключены ли уведомления приложения целиком в системных настройках. */
    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Контекст на языке, который выбрал пользователь.
     *
     * На API 33+ язык приложения применяет система, и это лишний шаг; на 26–32
     * его применяет `LocaleContextWrapper` в `MainActivity.attachBaseContext`,
     * то есть **только внутри Activity**. Каналы и пуши живут вне её:
     * `@ApplicationContext` там отдаёт строки на языке системы, и человек,
     * выбравший uz на русском телефоне, читал бы названия каналов по-русски.
     *
     * `LocaleContextWrapper` здесь не подходит: он вдобавок зовёт
     * `Locale.setDefault`, а менять локаль всего процесса из фонового сервиса
     * значит менять формат дат и чисел где угодно ещё.
     */
    private suspend fun localizedContext(): Context {
        val tag = settings.current().language.tag ?: return context
        return context.withLocale(Locale.forLanguageTag(tag))
    }
}

private fun Context.withLocale(locale: Locale): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    return createConfigurationContext(configuration)
}

/**
 * Тот же контекст на языке пользователя — нужен и [PushNotifier] для запасного
 * заголовка уведомления. `null` у [AppLanguage.tag] — «как в системе», тогда
 * подменять нечего.
 */
internal fun Context.localizedFor(language: AppLanguage): Context {
    val tag = language.tag ?: return this
    return withLocale(Locale.forLanguageTag(tag))
}

private fun NotificationCategory.toChannel(context: Context): NotificationChannel =
    NotificationChannel(id, context.getString(titleRes()), importance()).apply {
        description = context.getString(descriptionRes())
    }

/**
 * Важность канала при создании. Дальше её меняет только пользователь.
 *
 * `HIGH` (всплывающее уведомление) — ничему: пуш, перекрывающий экран, человек
 * выключает целиком вместе с остальными. `DEFAULT` со звуком — тому, что
 * требует действия сейчас; `LOW` без звука — маркетингу и прочему.
 */
private fun NotificationCategory.importance(): Int = when (this) {
    NotificationCategory.Orders,
    NotificationCategory.Queue,
    NotificationCategory.Bookings,
    NotificationCategory.Payments,
    -> NotificationManager.IMPORTANCE_DEFAULT

    NotificationCategory.Marketing,
    NotificationCategory.Other,
    -> NotificationManager.IMPORTANCE_LOW
}
