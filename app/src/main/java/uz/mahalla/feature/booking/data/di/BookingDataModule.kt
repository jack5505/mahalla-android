package uz.mahalla.feature.booking.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.booking.data.BookingApi
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.data.DefaultBookingRepository
import javax.inject.Singleton

/**
 * Бронирование (issue #97). API — на **основном** Retrofit: запись, список
 * своих записей и отмена требуют Bearer, а «голый» `@RefreshClient` его не
 * ставит. Услуги и слоты анонимны, но лишний заголовок им не мешает.
 */
@Module
@InstallIn(SingletonComponent::class)
object BookingDataModule {

    @Provides
    @Singleton
    fun provideBookingApi(retrofit: Retrofit): BookingApi =
        retrofit.create(BookingApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface BookingBindingsModule {

    @Binds
    fun bindBookingRepository(impl: DefaultBookingRepository): BookingRepository
}
