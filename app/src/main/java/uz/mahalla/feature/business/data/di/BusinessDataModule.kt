package uz.mahalla.feature.business.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.business.data.BusinessApi
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.data.DefaultBusinessRepository
import javax.inject.Singleton

/**
 * Бизнес-панель (эпик #16). API — на **основном** Retrofit: все шесть ручек
 * панели требуют Bearer (проверено curl'ом, каждая отвечает `401` без токена),
 * а «голый» `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object BusinessDataModule {

    @Provides
    @Singleton
    fun provideBusinessApi(retrofit: Retrofit): BusinessApi =
        retrofit.create(BusinessApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface BusinessBindingsModule {

    @Binds
    fun bindBusinessRepository(impl: DefaultBusinessRepository): BusinessRepository
}
