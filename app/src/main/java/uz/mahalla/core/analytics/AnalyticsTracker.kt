package uz.mahalla.core.analytics

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uz.mahalla.BuildConfig
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.network.analytics.AnalyticsRepository

/**
 * Точка входа для экранов: «выстрелил и забыл» (issue #169).
 *
 * Метод **не** suspend и **не** возвращает результат — намеренно. Экран не
 * должен ни ждать аналитику, ни узнавать про её отказ: человек пришёл записаться
 * к врачу, а не отправить событие, и падение сбора не имеет права ни задержать
 * подтверждение, ни показать ошибку.
 *
 * Интерфейс, а не прямой вызов репозитория, — по тем же двум причинам, что у
 * `CrashReporter` (issue #74): в тестах подставляется фейк, а замена сбора не
 * расползается по восьми ViewModel.
 */
interface AnalyticsTracker {

    fun track(event: AnalyticsEvent)
}

/**
 * Отправка на своей области видимости, а не на `viewModelScope`.
 *
 * Своя область нужна ровно за этим: события «заказ создан» и «талон взят»
 * отправляются одновременно с закрытием экрана, и на `viewModelScope` запрос
 * успел бы отмениться раньше, чем уйти. Область живёт столько же, сколько
 * граф ([scope] приходит из `AnalyticsModule` с `SupervisorJob`), поэтому
 * отказ одного события не гасит отправку следующих.
 *
 * Отказ пишется в лог **только в debug** и **только машинными полями** — вид
 * события и классификация ошибки ([reasonOf]). Ни `placeId`, ни текст сервера,
 * ни сообщение исключения в logcat не уезжают: то же правило, по которому
 * `CrashReporter` не принимает произвольные данные (риск T8 из issue #34).
 * Поэтому классификация собирается вручную, а не через `toString()`:
 * `ApiError.Http` несёт reason phrase сервера, `ApiError.Unexpected` —
 * сообщение исключения, и оба уехали бы в лог целиком.
 */
class DefaultAnalyticsTracker(
    private val repository: AnalyticsRepository,
    private val scope: CoroutineScope,
) : AnalyticsTracker {

    override fun track(event: AnalyticsEvent) {
        scope.launch {
            // Исключение здесь ловится, а не отдаётся области видимости: у
            // корутины без обработчика оно уходит в
            // `Thread.uncaughtExceptionHandler`, то есть роняет приложение.
            // `apiCall` внутри репозитория и так возвращает `Failure` вместо
            // броска, но аналитика — не тот код, который имеет право
            // рассчитывать на чужую аккуратность. `Error` (не `Exception`)
            // не ловится намеренно: `OutOfMemoryError` глотать нечестно.
            runCatchingCancellable { repository.track(event) }
                .onFailure { error -> log(event, error::class.java.simpleName) }
                .onSuccess { result ->
                    if (result is ApiResult.Failure) log(event, reasonOf(result.error))
                }
        }
    }

    private fun log(event: AnalyticsEvent, reason: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "${event.type.serverName} не отправлено: $reason")
        }
    }

    private companion object {
        const val TAG = "MahallaAnalytics"

        /**
         * Машинная классификация отказа — без единого поля, пришедшего от
         * сервера или введённого человеком. У `Business` берётся только
         * машинный код (`VALIDATION_ERROR`), у `Http` — только номер.
         */
        fun reasonOf(error: ApiError): String = when (error) {
            is ApiError.Business -> "Business(${error.code})"
            is ApiError.Http -> "Http(${error.code})"
            is ApiError.Unexpected -> "Unexpected(${error.cause?.let { it::class.java.simpleName }})"
            else -> error::class.java.simpleName
        }
    }
}
