package uz.mahalla.feature.discovery.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.discovery.data.CatalogApi
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.DataStoreSearchHistoryStore
import uz.mahalla.feature.discovery.data.DefaultCatalogRepository
import uz.mahalla.feature.discovery.data.SearchHistoryStore
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

/**
 * Репозиторий отдаётся через интерфейс: ViewModel'и эпика 4 тестируются с
 * фейком, а не с MockWebServer и Room.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DiscoveryBindingsModule {

    @Binds
    fun bindCatalogRepository(impl: DefaultCatalogRepository): CatalogRepository

    @Binds
    fun bindSearchHistoryStore(impl: DataStoreSearchHistoryStore): SearchHistoryStore
}
