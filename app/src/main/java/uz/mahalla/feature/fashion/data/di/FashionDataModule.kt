package uz.mahalla.feature.fashion.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.fashion.data.DefaultFashionCartRepository
import uz.mahalla.feature.fashion.data.DefaultFashionOrderRepository
import uz.mahalla.feature.fashion.data.DefaultFashionRepository
import uz.mahalla.feature.fashion.data.FashionApi
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionOrderRepository
import uz.mahalla.feature.fashion.data.FashionRepository
import javax.inject.Singleton

/**
 * Вертикаль «Одежда» (issue #108). API — на **основном** Retrofit: корзина и
 * заказы требуют Bearer, а «голый» `@RefreshClient` его не ставит. Каталог
 * анонимен, но лишний заголовок ему не мешает.
 */
@Module
@InstallIn(SingletonComponent::class)
object FashionDataModule {

    @Provides
    @Singleton
    fun provideFashionApi(retrofit: Retrofit): FashionApi =
        retrofit.create(FashionApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface FashionBindingsModule {

    @Binds
    fun bindFashionRepository(impl: DefaultFashionRepository): FashionRepository

    @Binds
    fun bindFashionCartRepository(impl: DefaultFashionCartRepository): FashionCartRepository

    @Binds
    fun bindFashionOrderRepository(impl: DefaultFashionOrderRepository): FashionOrderRepository
}
