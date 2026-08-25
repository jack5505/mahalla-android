package uz.mahalla.data.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Converter
import retrofit2.Retrofit
import uz.mahalla.BuildConfig
import uz.mahalla.data.network.AuthInterceptor
import uz.mahalla.data.network.BaseUrl
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.RefreshClient
import uz.mahalla.data.network.TokenAuthenticator
import uz.mahalla.data.network.auth.AuthApi
import javax.inject.Singleton

/**
 * Сетевой слой в графе (эпик 1.3).
 *
 * Клиентов два. Основной несёт `AuthInterceptor` (Bearer) и
 * `TokenAuthenticator` (refresh по 401). Второй, `@RefreshClient`, — «голый»:
 * им ходит сам refresh, иначе получился бы рекурсивный вызов.
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

    /** baseUrl задаётся buildType'ом — см. `app/build.gradle.kts`. */
    @Provides
    @BaseUrl
    fun provideBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(): OkHttpClient =
        NetworkFactory.clientBuilder(logBodies = BuildConfig.DEBUG).build()

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
    ): OkHttpClient = NetworkFactory.clientBuilder(logBodies = BuildConfig.DEBUG)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        converterFactory: Converter.Factory,
        @BaseUrl baseUrl: String,
    ): Retrofit = NetworkFactory.retrofit(baseUrl, client, converterFactory)
}
