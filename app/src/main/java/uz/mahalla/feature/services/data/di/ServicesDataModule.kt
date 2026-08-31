package uz.mahalla.feature.services.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.services.data.DefaultServicesRepository
import uz.mahalla.feature.services.data.ServicesApi
import uz.mahalla.feature.services.data.ServicesRepository
import javax.inject.Singleton

/**
 * Услуги (issue #71) на **основном** Retrofit: и заявка, и анкета требуют
 * Bearer, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesDataModule {

    @Provides
    @Singleton
    fun provideServicesApi(retrofit: Retrofit): ServicesApi =
        retrofit.create(ServicesApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface ServicesBindingsModule {

    @Binds
    fun bindServicesRepository(impl: DefaultServicesRepository): ServicesRepository
}
