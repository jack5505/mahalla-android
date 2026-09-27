package uz.mahalla.data.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Есть ли сеть прямо сейчас (issue #350), под Robolectric: настоящий
 * `ConnectivityManager` — `ShadowConnectivityManager` держит состояние в
 * статических полях, поэтому по умолчанию (без `setNetworkCapabilities`)
 * активная сеть есть, а её возможностей нет — то есть «не подключено».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConnectivityObserverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))

    @Test
    fun `without a registered capability the network counts as offline`() = runTest {
        val observer = AndroidConnectivityObserver(context)

        assertFalse(observer.isConnected.first())
    }

    @Test
    fun `the internet capability on the active network counts as online`() = runTest {
        markActiveNetworkConnected()
        val observer = AndroidConnectivityObserver(context)

        assertTrue(observer.isConnected.first())
    }

    @Test
    fun `losing the network is reported through the callback, not just at subscription`() =
        runTest(UnconfinedTestDispatcher()) {
            markActiveNetworkConnected()
            val observer = AndroidConnectivityObserver(context)
            val emissions = mutableListOf<Boolean>()
            val job = launch { observer.isConnected.toList(emissions) }

            shadowOf(manager).setDefaultNetworkActive(false)

            assertEquals(listOf(true, false), emissions)
            job.cancel()
        }

    private fun markActiveNetworkConnected() {
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
    }
}
