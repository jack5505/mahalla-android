package uz.mahalla.feature.notifications.push

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пуш пришёл, пока приложение открыто (issue #429).
 *
 * До этого о нём знала только шторка: [MahallaMessagingService] показывал
 * уведомление через [PushNotifier] и на этом останавливался, а бейдж
 * непрочитанного в топбаре (`NotificationsBadgeViewModel`) перечитывал
 * счётчик только на `init`/`ON_RESUME` — до следующего возврата на экран
 * показывал устаревшее значение.
 *
 * Сигнал зовётся **после** [PushNotifier.show], независимо от его решения:
 * тихие часы прячут показ в шторке, но запись на сервере остаётся
 * непрочитанной, и бейджу об этом всё равно нужно узнать.
 *
 * Без `replay`, как и [uz.mahalla.data.network.SessionExpiry]: пропущенное
 * событие не страшно — бейдж и так перечитывается на каждом `ON_RESUME`.
 * Буфер на одно событие — подряд пришедшие пуши значат одно и то же:
 * счётчик устарел.
 */
@Singleton
class NotificationsBadgeSignal @Inject constructor() {

    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val pushReceived: Flow<Unit> = events.asSharedFlow()

    /** Не suspend: зовётся из [MahallaMessagingService] под `runBlocking`. */
    fun notifyPushReceived() {
        events.tryEmit(Unit)
    }
}
