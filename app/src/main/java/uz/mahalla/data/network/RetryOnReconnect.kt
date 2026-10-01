package uz.mahalla.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Автоповтор последней загрузки при возврате сети (issue #350).
 *
 * Экран сам решает, что считать «ошибкой из-за сети» ([shouldRetry] проверяет
 * текущее состояние на месте вызова, а не запоминает его здесь): к моменту,
 * когда сеть вернулась, человек мог уже уйти с экрана или запустить повтор
 * вручную, и тогда второй, тем же самым, повтор не нужен.
 *
 * Первая эмиссия [ConnectivityObserver.isConnected] — это состояние на момент
 * подписки, а не «сеть появилась», но фильтр всё равно безопасен: если экран
 * в этот момент не показывает сетевую ошибку, [shouldRetry] сама вернёт `false`.
 */
fun CoroutineScope.retryOnReconnect(
    connectivity: ConnectivityObserver,
    shouldRetry: () -> Boolean,
    retry: () -> Unit,
) {
    launch {
        connectivity.isConnected
            .filter { connected -> connected }
            .collect { if (shouldRetry()) retry() }
    }
}
