package uz.mahalla.feature.profile.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.profile.data.DefaultSessionsRepository
import uz.mahalla.feature.profile.data.SessionsApi
import uz.mahalla.feature.profile.data.SessionsRepository
import javax.inject.Singleton

/**
 * Сессии устройств (issue #61) на **основном** Retrofit: запросы требуют
 * Bearer-токена, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProfileDataModule {

    @Provides
    @Singleton
    fun provideSessionsApi(retrofit: Retrofit): SessionsApi =
        retrofit.create(SessionsApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface ProfileBindingsModule {

    @Binds
    fun bindSessionsRepository(impl: DefaultSessionsRepository): SessionsRepository
}
