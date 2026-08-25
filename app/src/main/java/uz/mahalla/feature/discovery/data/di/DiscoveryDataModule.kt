package uz.mahalla.feature.discovery.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.discovery.data.CatalogApi
import javax.inject.Singleton

/** API фичи объявляются в её собственном модуле, а не в общем NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object DiscoveryDataModule {

    @Provides
    @Singleton
    fun provideCatalogApi(retrofit: Retrofit): CatalogApi =
        retrofit.create(CatalogApi::class.java)
}
