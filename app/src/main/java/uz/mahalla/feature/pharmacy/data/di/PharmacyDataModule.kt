package uz.mahalla.feature.pharmacy.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.pharmacy.data.DefaultPharmacyRepository
import uz.mahalla.feature.pharmacy.data.PharmacyApi
import uz.mahalla.feature.pharmacy.data.PharmacyRepository
import javax.inject.Singleton

/**
 * Витрина аптеки (issue #100). API — на **основном** Retrofit: сама ручка
 * анонимна (`200` без токена), но лишний `Authorization` ей не мешает, а
 * «голый» `@RefreshClient` нужен только тому, что обязано ходить без токена.
 */
@Module
@InstallIn(SingletonComponent::class)
object PharmacyDataModule {

    @Provides
    @Singleton
    fun providePharmacyApi(retrofit: Retrofit): PharmacyApi =
        retrofit.create(PharmacyApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface PharmacyBindingsModule {

    @Binds
    fun bindPharmacyRepository(impl: DefaultPharmacyRepository): PharmacyRepository
}
