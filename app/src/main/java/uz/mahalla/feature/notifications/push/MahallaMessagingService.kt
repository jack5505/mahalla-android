package uz.mahalla.feature.notifications.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.push.PushTokenRegistrar
import javax.inject.Inject

/**
 * Приём пушей (эпик 11).
 *
 * Оба метода Firebase зовёт **с фонового потока** своего исполнителя и держит
 * сервис живым, пока они не вернутся. Поэтому здесь `runBlocking`, а не
 * собственный scope: корутина, запущенная и брошенная, была бы убита вместе с
 * сервисом ровно на середине записи в DataStore.
 *
 * Логики здесь нет намеренно — только склейка: разбор payload'а живёт в
 * [PushMessage], решение о показе в [PushGate], показ в [PushNotifier]. Всё
 * это проверяется JVM-тестами, а сам сервис проверить нечем: эмулятора в CI
 * нет (AGENTS.md).
 */
@AndroidEntryPoint
class MahallaMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var notifier: PushNotifier

    @Inject
    lateinit var tokenRegistrar: PushTokenRegistrar

    /**
     * Firebase зовёт это при первой регистрации и при каждой смене токена —
     * после переустановки, очистки данных, восстановления из бэкапа. Токен
     * запоминается; на бэкенд он уедет со следующим запросом авторизации, ведь
     * отдельной ручки регистрации у того нет (см. [PushTokenRegistrar]).
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        runBlocking {
            runCatchingCancellable { tokenRegistrar.onTokenChanged(token) }
                .reportSwallowed("push.onNewToken")
        }
    }

    /**
     * Вызывается для сообщений с `data` — всегда, когда бы они ни пришли.
     *
     * У сообщения с блоком `notification` поведение другое: пока приложение в
     * фоне, его показывает сама библиотека, и ни каналы по категориям, ни
     * тихие часы к нему не применяются. Канал для такого случая задан в
     * манифесте (`default_notification_channel_id`), чтобы уведомление хотя бы
     * не попало в чужую категорию. Чтобы работало остальное, бэкенд обязан
     * слать **data-сообщения** — это записано в `docs/API-CONTRACT.md`.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val push = PushMessage.of(
            data = message.data,
            fallbackTitle = message.notification?.title,
            fallbackBody = message.notification?.body,
        )
        runBlocking {
            runCatchingCancellable { notifier.show(push) }
                .reportSwallowed("push.onMessageReceived")
        }
    }
}
