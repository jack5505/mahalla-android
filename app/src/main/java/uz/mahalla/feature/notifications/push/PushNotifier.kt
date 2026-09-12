package uz.mahalla.feature.notifications.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import uz.mahalla.MainActivity
import uz.mahalla.R
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.notifications.data.NotificationSettingsStore
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Показ пуша в системной шторке (эпик 11).
 *
 * Здесь только Android: решение «показывать ли» принимает [PushGate], и
 * проверяется оно обычным JVM-тестом. Эмулятора в CI нет (AGENTS.md), поэтому
 * всё, что нельзя разделить, должно быть максимально коротким.
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: NotificationChannels,
    private val settingsStore: NotificationSettingsStore,
    private val settingsDataStore: SettingsDataStore,
) {

    /**
     * @return принятое решение. Возвращается, а не проглатывается: «пуш пришёл,
     * но его не показали» — самая дорогая для разбора жалоба, и причина должна
     * быть хотя бы доступна вызывающему коду. Сейчас единственный вызывающий —
     * [MahallaMessagingService], и он решение не использует: писать его в лог
     * значит писать туда же, куда система уже пишет свои строки о доставке.
     */
    suspend fun show(message: PushMessage, now: LocalTime = LocalTime.now()): PushDecision {
        val category = message.category
        // Канал заводится до проверки: без него `isEnabled` отвечал бы «да» по
        // умолчанию у всех, и выключенный пользователем канал ничем не
        // отличался бы от ещё не созданного.
        channels.ensure(category)

        val decision = PushGate.decide(
            message = message,
            settings = settingsStore.current(),
            permissions = PushPermissions(
                permissionGranted = isPostNotificationsGranted(),
                notificationsEnabled = channels.areNotificationsEnabled(),
                channelEnabled = channels.isEnabled(category),
            ),
            now = now,
        )
        if (decision is PushDecision.Show) {
            // Запасной заголовок берётся на языке пользователя, а не системы:
            // приложение живёт вне Activity, и `@ApplicationContext` про
            // выбранный в профиле язык не знает (см. NotificationChannels).
            post(message, decision, context.localizedFor(settingsDataStore.current().language))
        }
        return decision
    }

    // Разрешение проверено в PushGate несколькими строками выше
    // (`isPostNotificationsGranted`), но статический анализ этого не видит:
    // проверка отделена от вызова ради тестируемости решения. Второе, чего
    // требует lint, — обработка SecurityException — сделана `runCatching`
    // ниже: отозвать разрешение между проверкой и показом можно, и падать на
    // этом фоновый сервис не должен.
    @SuppressLint("MissingPermission")
    private fun post(
        message: PushMessage,
        decision: PushDecision.Show,
        localized: Context,
    ) {
        val notification = NotificationCompat.Builder(context, decision.category.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                message.title ?: localized.getString(R.string.notifications_default_title),
            )
            .setContentText(message.body)
            // Текст уведомления пишет бэкенд и длину не ограничивает: без
            // BigTextStyle человек видел бы одну строку с многоточием.
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setAutoCancel(true)
            .setSilent(decision.silent)
            .setContentIntent(contentIntent(message))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(message.tag, NOTIFICATION_ID, notification)
        }.reportSwallowed("push_notify")
    }

    /**
     * Нажатие открывает deep link — тот же, по которому экран открывается
     * откуда угодно. Своего пути «из пуша» нет намеренно: у уведомления нет
     * доступа к `NavController`, а второй способ попасть на экран разошёлся бы
     * с первым при первой же правке.
     *
     * `FLAG_IMMUTABLE` обязателен с API 31 и правилен всегда: менять этот
     * intent чужому приложению незачем. `FLAG_UPDATE_CURRENT` — чтобы повторный
     * пуш того же уведомления вёл туда, куда ведёт новый, а не старый.
     *
     * `requestCode` завязан на ключ уведомления: одинаковый код склеил бы
     * разные пуши в один PendingIntent, и все они открывали бы первый экран.
     */
    private fun contentIntent(message: PushMessage): PendingIntent {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(message.deepLink),
            context,
            MainActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            message.tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * `POST_NOTIFICATIONS` появилось в API 33. Ниже разрешения не существует —
     * `checkSelfPermission` там вернул бы отказ по несуществующему имени, и
     * приложение молчало бы на всех старых устройствах.
     */
    private fun isPostNotificationsGranted(): Boolean =
        !needsPostNotificationsPermission() ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Уведомления различаются тегом ([PushMessage.tag]), а не числом:
         * система хранит их по паре «тег + id», и одного постоянного id хватает
         * — зато повторная доставка того же уведомления заменяет себя, а не
         * добавляет копию.
         */
        private const val NOTIFICATION_ID = 1
    }
}

/** Требуется ли на этом устройстве разрешение `POST_NOTIFICATIONS` (API 33+). */
fun needsPostNotificationsPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
