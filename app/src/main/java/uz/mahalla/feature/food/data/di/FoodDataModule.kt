package uz.mahalla.feature.food.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.DefaultCartRepository
import uz.mahalla.feature.food.data.DefaultMenuRepository
import uz.mahalla.feature.food.data.DefaultOrderRepository
import uz.mahalla.feature.food.data.DefaultWalletRepository
import uz.mahalla.feature.food.data.FoodApi
import uz.mahalla.feature.food.data.MenuRepository
import uz.mahalla.feature.food.data.OrderRepository
import uz.mahalla.feature.food.data.WalletRepository
import javax.inject.Singleton

/** API вертикали «Еда» (эпик 5) — в её собственном модуле, как в discovery. */
@Module
@InstallIn(SingletonComponent::class)
object FoodDataModule {

    @Provides
    @Singleton
    fun provideFoodApi(retrofit: Retrofit): FoodApi = retrofit.create(FoodApi::class.java)
}

/** Репозитории — через интерфейсы: ViewModel'и эпика 5 тестируются с фейками. */
@Module
@InstallIn(SingletonComponent::class)
interface FoodBindingsModule {

    @Binds
    fun bindMenuRepository(impl: DefaultMenuRepository): MenuRepository

    @Binds
    fun bindCartRepository(impl: DefaultCartRepository): CartRepository

    @Binds
    fun bindOrderRepository(impl: DefaultOrderRepository): OrderRepository

    @Binds
    fun bindWalletRepository(impl: DefaultWalletRepository): WalletRepository
}
