package uz.mahalla.feature.notifications.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.notifications.data.DefaultNotificationsRepository
import uz.mahalla.feature.notifications.data.NotificationsApi
import uz.mahalla.feature.notifications.data.NotificationsRepository
import javax.inject.Singleton

/**
 * Центр уведомлений (issue #81) на **основном** Retrofit: все три ручки
 * требуют Bearer, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationsDataModule {

    @Provides
    @Singleton
    fun provideNotificationsApi(retrofit: Retrofit): NotificationsApi =
        retrofit.create(NotificationsApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface NotificationsBindingsModule {

    @Binds
    fun bindNotificationsRepository(
        impl: DefaultNotificationsRepository,
    ): NotificationsRepository
}
