package uz.mahalla.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Есть ли сеть прямо сейчас (issue #350). За интерфейсом — ради тестов и
 * ради того, что баннер и автоповтор не должны знать про `ConnectivityManager`.
 */
interface ConnectivityObserver {

    /** Текущее состояние сразу при подписке, дальше — только смена значения. */
    val isConnected: Flow<Boolean>
}

@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityObserver {

    /**
     * `callbackFlow`, а не `MutableStateFlow` за синглтоном: подписчиков мало
     * (баннер в `MahallaApp`, автоповтор на экране), а колбэк системы живёт
     * ровно пока есть подписка — `awaitClose` снимает его, не оставляя
     * `ConnectivityManager` держать ссылку без надобности.
     */
    override val isConnected: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService<ConnectivityManager>()
        if (manager == null) {
            // Системного сервиса нет — соврать про офлайн хуже, чем не проверять вовсе.
            trySend(true)
            awaitClose {}
            return@callbackFlow
        }

        trySend(manager.isCurrentlyConnected())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(manager.isCurrentlyConnected())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/**
 * Проверка синхронная — читает её и колбэк выше, и [ConnectivityInterceptor]
 * на потоке OkHttp, где `Flow` не подписать.
 */
internal fun ConnectivityManager.isCurrentlyConnected(): Boolean {
    val network = activeNetwork ?: return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
