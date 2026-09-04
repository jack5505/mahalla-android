package uz.mahalla.feature.update.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.update.data.AppVersionApi
import uz.mahalla.feature.update.data.AppVersionRepository
import uz.mahalla.feature.update.data.DefaultAppVersionRepository
import javax.inject.Singleton

/**
 * Проверка версии (issue #80) на **основном** Retrofit: `app/version/check`
 * анонимен, а `app/version/skip` требует Bearer, который ставит только
 * основной клиент.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateDataModule {

    @Provides
    @Singleton
    fun provideAppVersionApi(retrofit: Retrofit): AppVersionApi =
        retrofit.create(AppVersionApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface UpdateBindingsModule {

    @Binds
    fun bindAppVersionRepository(impl: DefaultAppVersionRepository): AppVersionRepository
}
