package uz.mahalla.feature.promotions.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.promotions.data.DefaultPromotionsRepository
import uz.mahalla.feature.promotions.data.PromotionsApi
import uz.mahalla.feature.promotions.data.PromotionsRepository
import javax.inject.Singleton

/**
 * Акции (issue #104) на **основном** Retrofit. Обе читающие ручки анонимны, но
 * гео-заголовки им нужны, а их ставит `GeoHeaderInterceptor` на обоих
 * клиентах: лишний `Authorization` чтению не мешает, и разводить API по двум
 * клиентам незачем.
 */
@Module
@InstallIn(SingletonComponent::class)
object PromotionsDataModule {

    @Provides
    @Singleton
    fun providePromotionsApi(retrofit: Retrofit): PromotionsApi =
        retrofit.create(PromotionsApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface PromotionsBindingsModule {

    @Binds
    fun bindPromotionsRepository(impl: DefaultPromotionsRepository): PromotionsRepository
}
