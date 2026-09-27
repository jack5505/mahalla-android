package uz.mahalla.data.network

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Быстрый отказ без коннект-таймаута (issue #350).
 *
 * Без сети запрос сейчас уходит в `OkHttp` и ждёт `CONNECT_TIMEOUT_SECONDS`
 * (15 с, см. [NetworkFactory]), прежде чем `apiCall` превратит `IOException`
 * в [uz.mahalla.core.result.ApiError.NoConnection]. Этот интерцептор читает
 * состояние сети синхронно и бросает то же исключение немедленно — экран
 * получает тот же `ApiError.NoConnection`, но без ожидания.
 *
 * Первым в цепочке (см. `NetworkFactory.clientBuilder`): дальше стоят адрес
 * бэкенда, гео и авторизация, и ни один из них не должен успеть отработать
 * над запросом, который заведомо никуда не уйдёт.
 */
@Singleton
class ConnectivityInterceptor @Inject constructor(
    @ApplicationContext private val context: Context,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val manager = context.getSystemService<ConnectivityManager>()
        // Сервиса нет — не наше дело гадать, пропускаем запрос дальше как есть.
        if (manager != null && !manager.isCurrentlyConnected()) {
            throw IOException("No network connection")
        }
        return chain.proceed(chain.request())
    }
}
