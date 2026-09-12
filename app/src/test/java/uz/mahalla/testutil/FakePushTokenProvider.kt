package uz.mahalla.testutil

import uz.mahalla.data.push.PushTokenProvider

/**
 * Токен пушей без Firebase: в JVM-тесте `FirebaseMessaging` не поднимается
 * вовсе, а проверять нужно то, что происходит вокруг него.
 *
 * @param token `null` — Firebase не настроен или не ответил: штатный случай,
 * а не сбой (сборка без `google-services.json` нормальна).
 */
class FakePushTokenProvider(
    var token: String? = null,
) : PushTokenProvider {

    var calls: Int = 0
        private set

    override suspend fun token(): String? {
        calls++
        return token
    }
}
