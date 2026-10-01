package uz.mahalla.data.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Быстрый отказ без коннект-таймаута (issue #350): без сети запрос не должен
 * дойти до OkHttp вовсе, а не упасть только через `CONNECT_TIMEOUT_SECONDS`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConnectivityInterceptorTest {

    private lateinit var server: MockWebServer
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `no network fails immediately and never reaches the server`() {
        val client = OkHttpClient.Builder().addInterceptor(ConnectivityInterceptor(context)).build()

        assertThrows(IOException::class.java) { call(client) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a connected network reaches the server as usual`() {
        markActiveNetworkConnected()
        val client = OkHttpClient.Builder().addInterceptor(ConnectivityInterceptor(context)).build()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = call(client)

        assertEquals(200, response.code)
        response.close()
    }

    private fun call(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/ping")).build()).execute()

    private fun markActiveNetworkConnected() {
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
    }
}
