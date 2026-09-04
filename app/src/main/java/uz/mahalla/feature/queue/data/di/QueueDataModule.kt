package uz.mahalla.feature.queue.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.queue.data.DataStoreWalkInTicketStore
import uz.mahalla.feature.queue.data.DefaultWalkInRepository
import uz.mahalla.feature.queue.data.WalkInApi
import uz.mahalla.feature.queue.data.WalkInRepository
import uz.mahalla.feature.queue.data.WalkInTicketStore
import javax.inject.Singleton

/**
 * Очередь (issue #96). API — на **основном** Retrofit: и `walkin/send`, и
 * `walkin/{id}/cancel` требуют Bearer, а «голый» `@RefreshClient` его не
 * ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object QueueDataModule {

    @Provides
    @Singleton
    fun provideWalkInApi(retrofit: Retrofit): WalkInApi = retrofit.create(WalkInApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface QueueBindingsModule {

    @Binds
    fun bindWalkInRepository(impl: DefaultWalkInRepository): WalkInRepository

    @Binds
    fun bindWalkInTicketStore(impl: DataStoreWalkInTicketStore): WalkInTicketStore
}
