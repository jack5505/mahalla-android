package uz.mahalla.data.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Converter
import retrofit2.Retrofit
import uz.mahalla.BuildConfig
import uz.mahalla.data.network.AndroidCleartextPolicy
import uz.mahalla.data.network.AuthInterceptor
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendReachability
import uz.mahalla.data.network.BackendUrlInterceptor
import uz.mahalla.data.network.BackendUrlOverride
import uz.mahalla.data.network.BaseUrl
import uz.mahalla.data.network.CleartextPolicy
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.OkHttpBackendReachability
import uz.mahalla.data.network.RefreshClient
import uz.mahalla.data.network.TokenAuthenticator
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.inspector.ChuckerHttpInspector
import uz.mahalla.data.network.inspector.HttpInspector
import uz.mahalla.data.network.tls.CertificatePinSource
import javax.inject.Singleton

/**
 * Сетевой слой в графе (эпик 1.3).
 *
 * Клиентов два. Основной несёт `AuthInterceptor` (Bearer) и
 * `TokenAuthenticator` (refresh по 401). Второй, `@RefreshClient`, — «голый»:
 * им ходит сам refresh, иначе получился бы рекурсивный вызов.
 *
 * Доверие к самоподписанному сертификату (issue #32) уходит в клиенты только
 * там, где на него вообще есть право (`BACKEND_URL_OVERRIDE`). В остальных
 * сборках передаётся `null`, и TLS остаётся ровно платформенным — своей
 * `sslSocketFactory` у клиента нет, подменить проверку нечем.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = NetworkFactory.json()

    @Provides
    @Singleton
    fun provideConverterFactory(json: Json): Converter.Factory =
        NetworkFactory.converterFactory(json)

    /**
     * baseUrl сборки — см. `app/build.gradle.kts`. Это адрес по умолчанию и
     * шаблон пути: фактический адрес пользователь задаёт на первом экране, и
     * запросы на него переводит [BackendUrlInterceptor] (issue #26).
     */
    @Provides
    @BaseUrl
    fun provideBaseUrl(): String = BuildConfig.API_BASE_URL

    /**
     * Право менять адрес бэкенда: debug — да, release — только если сборку
     * собрали с `BACKEND_URL_OVERRIDE=true` (issue #26).
     */
    @Provides
    @BackendUrlOverride
    fun provideBackendUrlOverride(): Boolean = BuildConfig.BACKEND_URL_OVERRIDE

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(
        backendUrlInterceptor: BackendUrlInterceptor,
        httpInspector: HttpInspector,
        certificatePin: BackendCertificatePin,
        @BackendUrlOverride overrideEnabled: Boolean,
    ): OkHttpClient = NetworkFactory.refreshClient(
        backendUrlInterceptor = backendUrlInterceptor,
        inspector = httpInspector.interceptor,
        logBodies = BuildConfig.DEBUG,
        certificatePin = certificatePin.takeIf { overrideEnabled },
    )

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshRetrofit(
        @RefreshClient client: OkHttpClient,
        converterFactory: Converter.Factory,
        @BaseUrl baseUrl: String,
    ): Retrofit = NetworkFactory.retrofit(baseUrl, client, converterFactory)

    @Provides
    @Singleton
    fun provideAuthApi(@RefreshClient retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        backendUrlInterceptor: BackendUrlInterceptor,
        httpInspector: HttpInspector,
        certificatePin: BackendCertificatePin,
        @BackendUrlOverride overrideEnabled: Boolean,
    ): OkHttpClient = NetworkFactory.mainClient(
        backendUrlInterceptor = backendUrlInterceptor,
        authInterceptor = authInterceptor,
        authenticator = tokenAuthenticator,
        inspector = httpInspector.interceptor,
        logBodies = BuildConfig.DEBUG,
        certificatePin = certificatePin.takeIf { overrideEnabled },
    )

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        converterFactory: Converter.Factory,
        @BaseUrl baseUrl: String,
    ): Retrofit = NetworkFactory.retrofit(baseUrl, client, converterFactory)
}

@Module
@InstallIn(SingletonComponent::class)
interface NetworkBindingsModule {

    @Binds
    fun bindBackendReachability(impl: OkHttpBackendReachability): BackendReachability

    @Binds
    fun bindCleartextPolicy(impl: AndroidCleartextPolicy): CleartextPolicy

    /**
     * Доверие к сертификату, подтверждённому пользователем (issue #32). Пин
     * читают клиенты во время handshake и проверка адреса перед сохранением.
     */
    @Binds
    fun bindCertificatePinSource(impl: BackendCertificatePin): CertificatePinSource

    /** Инспектор трафика (issue #30). В release реализация отвечает «нет». */
    @Binds
    fun bindHttpInspector(impl: ChuckerHttpInspector): HttpInspector
}
