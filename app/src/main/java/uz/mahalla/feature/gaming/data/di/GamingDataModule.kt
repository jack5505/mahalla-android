package uz.mahalla.feature.gaming.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.gaming.data.DefaultGamingRepository
import uz.mahalla.feature.gaming.data.GamingApi
import uz.mahalla.feature.gaming.data.GamingRepository
import javax.inject.Singleton

/**
 * Игровые зоны (issue #98). API — на **основном** Retrofit: бронь и «мои
 * брони» требуют Bearer, а «голый» `@RefreshClient` его не ставит. Список зон
 * анонимен, но отдельный клиент ради него завёл бы второй пул соединений.
 */
@Module
@InstallIn(SingletonComponent::class)
object GamingDataModule {

    @Provides
    @Singleton
    fun provideGamingApi(retrofit: Retrofit): GamingApi = retrofit.create(GamingApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface GamingBindingsModule {

    @Binds
    fun bindGamingRepository(impl: DefaultGamingRepository): GamingRepository
}
