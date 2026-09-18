package uz.mahalla.data.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.mahalla.BuildConfig
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Откуда берётся токен FCM. Интерфейс — ради тестов: `FirebaseMessaging` в
 * JVM-тесте не поднимается вовсе, а проверить надо поведение вокруг него.
 */
interface PushTokenProvider {

    /** `null` — токена нет: Firebase не настроен в сборке или не ответил. */
    suspend fun token(): String?
}

/**
 * Регистрация и обновление токена пушей (эпик 11).
 *
 * **Отдельного запроса регистрации нет** — и это не упущение клиента: в схеме
 * бэкенда (`/v3/api-docs`, сверка 2026-09-09) нет ни `devices`, ни
 * `push/register`, ни чего-либо подобного. Токен он принимает единственным
 * способом: полем `fcmToken` внутри `AuthDeviceInfo`, то есть вместе с
 * `send-otp`, `verify-otp`, `pin-login`, `refresh` и входом через Telegram.
 *
 * Отсюда весь дизайн: токен кладётся в [PushTokenStore], а
 * `AndroidDeviceInfoProvider` подставляет его в описание устройства. Дальше он
 * уезжает сам — с ближайшим продлением сессии или входом. Задержка на практике
 * равна времени жизни access-токена; появится у бэкенда своя ручка — здесь
 * добавится один вызов, а всё остальное останется как есть.
 *
 * [sync] вызывается на старте приложения: `onNewToken` срабатывает только когда
 * токен **меняется**, а первый после установки приложение обязано спросить
 * само.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val tokenProvider: PushTokenProvider,
    private val tokenStore: PushTokenStore,
) {

    /** Спросить текущий токен и запомнить его, если он изменился. */
    suspend fun sync() {
        val token = tokenProvider.token() ?: return
        onTokenChanged(token)
    }

    /**
     * Новый токен от Firebase (`onNewToken`) либо тот же самый со старта.
     * Одинаковый токен не переписывается: запись в DataStore будит всех
     * подписчиков `data`, а менять при этом нечего.
     */
    suspend fun onTokenChanged(token: String) {
        if (tokenStore.current() == token.trim()) return
        runCatchingCancellable { tokenStore.save(token) }
            .reportSwallowed("push.saveToken")
    }
}

/**
 * Токен из Firebase.
 *
 * Без `google-services.json` `FirebaseApp` не создаётся, а
 * `FirebaseMessaging.getInstance()` на этом кидает `IllegalStateException` —
 * поэтому сперва проверка, что приложение Firebase вообще есть. Это штатный
 * случай, а не сбой: сборка без ключей нормальна (см. `app/build.gradle.kts`).
 */
@Singleton
class FirebasePushTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : PushTokenProvider {

    override suspend fun token(): String? {
        if (!BuildConfig.PUSH_ENABLED || FirebaseApp.getApps(context).isEmpty()) return null
        return runCatchingCancellable { awaitToken() }
            .reportSwallowed("push.token")
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * `Task` → корутина руками: `kotlinx-coroutines-play-services` ради одного
     * `await()` тянуть незачем, а блокирующий `Tasks.await()` вставал бы на
     * потоке, с которого его позовут.
     */
    private suspend fun awaitToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            // Отказ (нет сервисов Google, нет сети) — это «токена нет», а не
            // падение: пуши просто не придут, остальное приложение работает.
            // `task.result` у неуспешной задачи кидает исключение, поэтому
            // сперва проверка, а не `takeIf` после обращения.
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
