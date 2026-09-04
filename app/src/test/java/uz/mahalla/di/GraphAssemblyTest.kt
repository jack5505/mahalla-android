package uz.mahalla.di

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.crash.NoopCrashReporter
import uz.mahalla.core.crash.di.CrashModule
import uz.mahalla.core.di.AppModule
import uz.mahalla.data.db.di.DatabaseModule
import uz.mahalla.data.device.AndroidDeviceInfoProvider
import uz.mahalla.data.device.DeviceIdStore
import uz.mahalla.data.location.AndroidLocationSource
import uz.mahalla.data.location.DefaultRequestLocationProvider
import uz.mahalla.data.network.AuthInterceptor
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendUrlInterceptor
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.GeoHeaderInterceptor
import uz.mahalla.data.network.TokenAuthenticator
import uz.mahalla.data.network.di.NetworkModule
import uz.mahalla.data.network.inspector.ChuckerHttpInspector
import uz.mahalla.data.network.tls.PinnedCertificateHostnameVerifier
import uz.mahalla.data.prefs.DataStoreSessionStore
import uz.mahalla.data.prefs.DataStoreUserProfileStore
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.prefs.di.DataStoreModule
import uz.mahalla.data.security.AndroidKeystorePinCipher
import uz.mahalla.data.security.KeystorePinStorage
import uz.mahalla.feature.auth.data.DefaultAuthRepository
import uz.mahalla.feature.discovery.data.DataStoreSearchHistoryStore
import uz.mahalla.feature.discovery.data.DefaultCatalogRepository
import uz.mahalla.feature.discovery.data.di.DiscoveryDataModule
import uz.mahalla.feature.food.data.DefaultCartRepository
import uz.mahalla.feature.food.data.DefaultMenuRepository
import uz.mahalla.feature.food.data.DefaultOrderRepository
import uz.mahalla.feature.food.data.di.FoodDataModule
import uz.mahalla.feature.notifications.data.DefaultNotificationsRepository
import uz.mahalla.feature.notifications.data.di.NotificationsDataModule
import uz.mahalla.feature.onboarding.data.DataStoreOnboardingRepository
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.data.DataStoreRoleRepository
import uz.mahalla.feature.role.data.DefaultProviderRepository
import uz.mahalla.feature.role.data.di.RoleDataModule
import uz.mahalla.feature.wallet.data.DefaultWalletRepository
import uz.mahalla.feature.update.data.AppUpdateGate
import uz.mahalla.feature.update.data.DefaultAppVersionRepository
import uz.mahalla.feature.update.data.di.UpdateDataModule
import uz.mahalla.feature.wallet.data.di.WalletDataModule

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
        val refreshClient = refreshClient(backendUrlInterceptor)
        val refreshRetrofit =
            NetworkModule.provideRefreshRetrofit(refreshClient, converterFactory, baseUrl)
        val authApi = NetworkModule.provideAuthApi(refreshRetrofit)

        val sessionStore = DataStoreSessionStore(sharedDataStore(context))
        val client = NetworkModule.provideOkHttpClient(
            authInterceptor = AuthInterceptor(sessionStore),
            tokenAuthenticator = TokenAuthenticator(
                sessionStore = sessionStore,
                authApi = authApi,
                deviceInfoProvider = deviceInfoProvider(context),
                locationProvider = locationProvider(context),
                clock = AppModule.provideClock(),
            ),
            backendUrlInterceptor = backendUrlInterceptor,
            geoHeaderInterceptor = geoHeaderInterceptor(),
            httpInspector = inspector(),
            certificatePin = certificatePin(),
            overrideEnabled = true,
        )
        val retrofit = NetworkModule.provideRetrofit(client, converterFactory, baseUrl)

        assertNotNull(DiscoveryDataModule.provideCatalogApi(retrofit))
        // Refresh-клиент обязан быть без authenticator'а: иначе 401 на сам
        // refresh снова позовёт его и получится рекурсия.
        assertTrue(client.authenticator is TokenAuthenticator)
        assertFalse(refreshClient.authenticator is TokenAuthenticator)
    }

    /**
     * Сборка без права менять адрес (release) не должна получать ни своей
     * `sslSocketFactory`, ни своего верификатора имени (issue #32): доверять
     * там нечему, а подменённый TLS — лишний способ сломать сеть целиком.
     */
    @Test
    fun `a build without the override keeps platform tls`() {
        val pinned = refreshClient(overrideEnabled = true)
        val platform = refreshClient(overrideEnabled = false)

        assertTrue(pinned.hostnameVerifier is PinnedCertificateHostnameVerifier)
        assertFalse(platform.hostnameVerifier is PinnedCertificateHostnameVerifier)
        assertEquals(
            "верификатор имени остался штатным",
            OkHttpClient().hostnameVerifier,
            platform.hostnameVerifier,
        )
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
                refreshClient(),
                NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
                NetworkModule.provideBaseUrl(),
            )
            assertNotNull(
                DefaultCatalogRepository(
                    api = DiscoveryDataModule.provideCatalogApi(retrofit),
                    placeDao = DatabaseModule.providePlaceDao(database),
                    locationProvider = locationProvider(context),
                    clock = AppModule.provideClock(),
                ),
            )
            assertNotNull(DataStoreSearchHistoryStore(sharedDataStore(context)))
        } finally {
            database.close()
        }
    }

    /** Вертикаль «Еда» (эпик 5): API, корзина в Room, заказы и кошелёк. */
    @Test
    fun `food graph assembles over the api, the database and the cart`() {
        val database = DatabaseModule.provideDatabase(context)
        try {
            // Клиент здесь любой: проверяется сборка репозиториев еды, а не
            // сетевой стек (для него есть свои тесты выше). Голый OkHttp вместо
            // NetworkModule.provideRefreshClient() ещё и не ломает этот тест
            // каждый раз, когда у провайдера клиента появляется новый параметр.
            val retrofit = NetworkModule.provideRetrofit(
                okhttp3.OkHttpClient(),
                NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
                NetworkModule.provideBaseUrl(),
            )
            val api = FoodDataModule.provideFoodApi(retrofit)
            val cartRepository = DefaultCartRepository(DatabaseModule.provideCartDraftDao(database))

            assertNotNull(DefaultMenuRepository(api))
            // Кошелёк живёт в своём модуле и на своих ручках (issue #62):
            // `wallet/balance` у бэкенда никогда не было.
            assertNotNull(
                DefaultWalletRepository(WalletDataModule.provideWalletApi(retrofit)),
            )
            assertNotNull(
                DefaultOrderRepository(
                    api = api,
                    orderDao = DatabaseModule.provideOrderDao(database),
                    cartRepository = cartRepository,
                    clock = AppModule.provideClock(),
                ),
            )
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
        val refreshClient = refreshClient()
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
            userProfileStore = DataStoreUserProfileStore(dataStore),
            pinStorage = KeystorePinStorage(dataStore, AndroidKeystorePinCipher()),
            deviceInfoProvider = deviceInfoProvider(context),
            locationProvider = locationProvider(context),
            clock = AppModule.provideClock(),
        )

        assertNotNull(repository)
        assertFalse(refreshClient.authenticator is TokenAuthenticator)
    }

    /**
     * Проверка версии (issue #80) собирается на **основном** Retrofit: `check`
     * анонимен, а `skip` требует Bearer, который ставит только он. Api создаётся
     * на голом `OkHttpClient` по той же причине, что и в food-графе: у
     * `provideRefreshClient` параметры прибавляются с каждым сетевым issue, и
     * этот тест ломался бы на каждом.
     */
    @Test
    fun `version check assembles on the main retrofit`() {
        val retrofit = NetworkModule.provideRetrofit(
            okhttp3.OkHttpClient(),
            NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
            NetworkModule.provideBaseUrl(),
        )

        val api = UpdateDataModule.provideAppVersionApi(retrofit)

        assertNotNull(api)
        assertNotNull(AppUpdateGate(DefaultAppVersionRepository(api)))
    }

    /**
     * Центр уведомлений (issue #81) — тоже на **основном** Retrofit: все три
     * ручки требуют Bearer, а «голый» `@RefreshClient` его не ставит.
     */
    @Test
    fun `notifications assemble on the main retrofit`() {
        val retrofit = NetworkModule.provideRetrofit(
            okhttp3.OkHttpClient(),
            NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
            NetworkModule.provideBaseUrl(),
        )

        val api = NotificationsDataModule.provideNotificationsApi(retrofit)

        assertNotNull(api)
        assertNotNull(DefaultNotificationsRepository(api))
    }

    /**
     * Анкеты (issue #84): заявка продавца уходит в `POST /places`, а он
     * требует Bearer — значит API собирается на **основном** Retrofit. Роль и
     * анкета покупателя живут в DataStore: профиля пользователя у бэкенда нет.
     */
    @Test
    fun `role forms assemble on the main retrofit and the data store`() {
        val dataStore = sharedDataStore(context)
        val retrofit = NetworkModule.provideRetrofit(
            okhttp3.OkHttpClient(),
            NetworkModule.provideConverterFactory(NetworkModule.provideJson()),
            NetworkModule.provideBaseUrl(),
        )

        val api = RoleDataModule.provideProviderApi(retrofit)

        assertNotNull(api)
        assertNotNull(
            DefaultProviderRepository(
                api = api,
                locationSource = AndroidLocationSource(context),
                // «Мои заведения» (issue #94): переключатель доступности
                // отправляет координаты устройства той же лестницей, что и
                // запросы авторизации.
                requestLocation = locationProvider(context),
                phoneValidator = PhoneNumberValidator(),
            ),
        )
        assertNotNull(
            DataStoreRoleRepository(
                settings = SettingsDataStore(dataStore),
                profileStore = DataStoreUserProfileStore(dataStore),
            ),
        )
    }

    /**
     * Отчёты о падениях (issue #74). В тестовой сборке секрета `SENTRY_DSN`
     * нет, и граф обязан отдать заглушку: SDK тогда не поднимается вовсе, а
     * не поднимается «вхолостую» с пустым адресом.
     */
    @Test
    fun `crash graph falls back to the noop reporter without a dsn`() {
        val config = CrashModule.provideCrashReportingConfig()
        val reporter = CrashModule.provideCrashReporter(context, config)

        // Юнит-тесты идут по debug-конфигурации, а там сбор выключен даже с
        // заданным секретом: включает его только SENTRY_ENABLED_IN_DEBUG.
        assertEquals("debug", config.environment)
        assertFalse(config.isEnabled)
        assertTrue(reporter === NoopCrashReporter)
        assertFalse(reporter.isEnabled)
        // Версия сборки в отчёте обязательна: без неё непонятно, где падает.
        assertTrue(config.release, config.release.startsWith("uz.mahalla@"))
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

    /**
     * Доверие к сертификату сервера (issue #32): пин обязан доезжать до обоих
     * клиентов — вход и refresh идут по «голому», всё остальное по основному.
     */
    private fun certificatePin() =
        BackendCertificatePin(SettingsDataStore(sharedDataStore(context)))

    private fun refreshClient(
        backendUrlInterceptor: BackendUrlInterceptor = backendUrlInterceptor(),
        overrideEnabled: Boolean = true,
    ) = NetworkModule.provideRefreshClient(
        backendUrlInterceptor = backendUrlInterceptor,
        geoHeaderInterceptor = geoHeaderInterceptor(),
        httpInspector = inspector(),
        certificatePin = certificatePin(),
        overrideEnabled = overrideEnabled,
    )

    /**
     * Инспектор трафика (issue #30) — настоящий: граф собирается вместе с
     * Chucker'ом, значит его интерцептор доезжает до обоих клиентов.
     */
    private fun inspector() = ChuckerHttpInspector(context)

    /**
     * Устройство и координаты — обязательные поля запросов авторизации
     * (issue #42): и репозиторий, и `TokenAuthenticator` собираются вместе с
     * ними, реализациями из графа.
     */
    private fun deviceInfoProvider(context: Context) =
        AndroidDeviceInfoProvider(DeviceIdStore(sharedDataStore(context)))

    /**
     * Координаты в заголовках каждого запроса (issue #53): без них бэкенд
     * отвечает 403 `GEO_PERMISSION_REQUIRED` ещё до маршрутизации.
     */
    private fun geoHeaderInterceptor() = GeoHeaderInterceptor(
        locationProvider = locationProvider(context),
        clock = AppModule.provideClock(),
    )

    private fun locationProvider(context: Context) = DefaultRequestLocationProvider(
        locationSource = AndroidLocationSource(context),
        settings = SettingsDataStore(sharedDataStore(context)),
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
