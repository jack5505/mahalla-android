package uz.mahalla.di

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.di.AppModule
import uz.mahalla.data.db.di.DatabaseModule
import uz.mahalla.data.network.AuthInterceptor
import uz.mahalla.data.network.BackendUrlInterceptor
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.TokenAuthenticator
import uz.mahalla.data.network.di.NetworkModule
import uz.mahalla.data.prefs.DataStoreSessionStore
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.di.DataStoreModule
import uz.mahalla.data.security.AndroidKeystorePinCipher
import uz.mahalla.data.security.KeystorePinStorage
import uz.mahalla.feature.auth.data.DefaultAuthRepository
import uz.mahalla.feature.discovery.data.DataStoreSearchHistoryStore
import uz.mahalla.feature.discovery.data.DefaultCatalogRepository
import uz.mahalla.feature.discovery.data.di.DiscoveryDataModule
import uz.mahalla.feature.onboarding.data.DataStoreOnboardingRepository

/**
 * Сборка графа (эпик 1.1).
 *
 * Провайдеры вызываются напрямую, а не через Hilt-рантайм: так тест собирает
 * ровно те же объекты, что соберёт Hilt, но не тащит в unit-тесты
 * `HiltTestApplication`. Отдельно проверяется, что кодогенерация Hilt
 * действительно произошла — иначе «граф собрался» ничего не значит.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GraphAssemblyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `network graph assembles end to end`() {
        val json = NetworkModule.provideJson()
        val converterFactory = NetworkModule.provideConverterFactory(json)
        val baseUrl = NetworkModule.provideBaseUrl()

        val backendUrlInterceptor = backendUrlInterceptor(baseUrl)
        val refreshClient = NetworkModule.provideRefreshClient(backendUrlInterceptor)
        val refreshRetrofit =
            NetworkModule.provideRefreshRetrofit(refreshClient, converterFactory, baseUrl)
        val authApi = NetworkModule.provideAuthApi(refreshRetrofit)

        val sessionStore = DataStoreSessionStore(sharedDataStore(context))
        val client = NetworkModule.provideOkHttpClient(
            authInterceptor = AuthInterceptor(sessionStore),
            tokenAuthenticator = TokenAuthenticator(
                sessionStore = sessionStore,
                authApi = authApi,
                clock = AppModule.provideClock(),
            ),
            backendUrlInterceptor = backendUrlInterceptor,
        )
        val retrofit = NetworkModule.provideRetrofit(client, converterFactory, baseUrl)

        assertNotNull(DiscoveryDataModule.provideCatalogApi(retrofit))
        // Refresh-клиент обязан быть без authenticator'а: иначе 401 на сам
        // refresh снова позовёт его и получится рекурсия.
        assertTrue(client.authenticator is TokenAuthenticator)
        assertFalse(refreshClient.authenticator is TokenAuthenticator)
    }

    @Test
    fun `base url ends with a slash as Retrofit requires`() {
        val baseUrl = NetworkModule.provideBaseUrl()
        assertTrue("baseUrl='$baseUrl'", baseUrl.endsWith("/"))
    }

    @Test
    fun `database graph exposes every dao`() {
        val database = DatabaseModule.provideDatabase(context)
        try {
            assertNotNull(DatabaseModule.providePlaceDao(database))
            assertNotNull(DatabaseModule.provideOrderDao(database))
            assertNotNull(DatabaseModule.provideCartDraftDao(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun `discovery graph assembles over the api, the dao and the clock`() {
        val database = DatabaseModule.provideDatabase(context)
        try {
            val retrofit = NetworkModule.provideRetrofit(
                NetworkModule.provideRefreshClient(backendUrlInterceptor()),
                NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
                NetworkModule.provideBaseUrl(),
            )
            assertNotNull(
                DefaultCatalogRepository(
                    api = DiscoveryDataModule.provideCatalogApi(retrofit),
                    placeDao = DatabaseModule.providePlaceDao(database),
                    clock = AppModule.provideClock(),
                ),
            )
            assertNotNull(DataStoreSearchHistoryStore(sharedDataStore(context)))
        } finally {
            database.close()
        }
    }

    @Test
    fun `storage graph assembles on top of a single data store`() {
        val dataStore = sharedDataStore(context)

        assertNotNull(DataStoreOnboardingRepository(SettingsDataStore(dataStore)))
        // Конструктор шифра не должен трогать Keystore — иначе граф не
        // собрался бы ни в тестах, ни на устройствах без AndroidKeyStore.
        assertNotNull(KeystorePinStorage(dataStore, AndroidKeystorePinCipher()))
    }

    /**
     * Авторизация (эпик 3) собирается на «голом» refresh-клиенте: 401 на
     * запросе кода или на logout не должен запускать обновление токена.
     */
    @Test
    fun `auth repository assembles on the bare refresh client`() {
        val converterFactory = NetworkModule.provideConverterFactory(NetworkModule.provideJson())
        val refreshClient = NetworkModule.provideRefreshClient(backendUrlInterceptor())
        val authApi = NetworkModule.provideAuthApi(
            NetworkModule.provideRefreshRetrofit(
                refreshClient,
                converterFactory,
                NetworkModule.provideBaseUrl(),
            ),
        )
        val dataStore = sharedDataStore(context)

        val repository = DefaultAuthRepository(
            authApi = authApi,
            sessionStore = DataStoreSessionStore(dataStore),
            pinStorage = KeystorePinStorage(dataStore, AndroidKeystorePinCipher()),
            clock = AppModule.provideClock(),
        )

        assertNotNull(repository)
        assertFalse(refreshClient.authenticator is TokenAuthenticator)
    }

    @Test
    fun `hilt generated the application component`() {
        assertNotNull(loadClass("uz.mahalla.Hilt_MahallaApplication"))
        assertNotNull(loadClass("uz.mahalla.MahallaApplication_HiltComponents"))
    }

    /**
     * Адрес бэкенда задаёт пользователь (issue #26), поэтому интерцептор,
     * переводящий запрос на этот адрес, обязан висеть на обоих клиентах.
     */
    private fun backendUrlInterceptor(
        baseUrl: String = NetworkModule.provideBaseUrl(),
    ) = BackendUrlInterceptor(
        BackendUrlStore(SettingsDataStore(sharedDataStore(context)), baseUrl),
        baseUrl,
    )

    /** `initialize = false`: нужен факт кодогенерации, а не статик-инициализация. */
    private fun loadClass(name: String): Class<*> =
        Class.forName(name, false, javaClass.classLoader)

    private companion object {
        /**
         * DataStore допускает ровно один экземпляр на файл в процессе, а
         * методы одного тест-класса Robolectric делят classloader — поэтому
         * экземпляр создаётся один раз на класс.
         */
        private var cached: DataStore<Preferences>? = null

        fun sharedDataStore(context: Context): DataStore<Preferences> =
            cached ?: DataStoreModule.providePreferencesDataStore(context).also { cached = it }
    }
}
