package uz.mahalla.feature.activity.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.activity.data.ActivityApi
import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.data.DefaultActivityRepository
import javax.inject.Singleton

/**
 * «Мои активности» (issue #73) на **основном** Retrofit: все пять ручек
 * требуют Bearer, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object ActivityDataModule {

    @Provides
    @Singleton
    fun provideActivityApi(retrofit: Retrofit): ActivityApi =
        retrofit.create(ActivityApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface ActivityBindingsModule {

    @Binds
    fun bindActivityRepository(impl: DefaultActivityRepository): ActivityRepository
}
