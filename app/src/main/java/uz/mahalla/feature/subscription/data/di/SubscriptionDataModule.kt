package uz.mahalla.feature.subscription.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.subscription.data.DefaultSubscriptionRepository
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.data.SubscriptionsApi
import javax.inject.Singleton

/**
 * Подписки (issue #103) на **основном** Retrofit: все ручки контроллера
 * требуют Bearer — даже список тарифов, — а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object SubscriptionDataModule {

    @Provides
    @Singleton
    fun provideSubscriptionsApi(retrofit: Retrofit): SubscriptionsApi =
        retrofit.create(SubscriptionsApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface SubscriptionBindingsModule {

    @Binds
    fun bindSubscriptionRepository(
        impl: DefaultSubscriptionRepository,
    ): SubscriptionRepository
}
