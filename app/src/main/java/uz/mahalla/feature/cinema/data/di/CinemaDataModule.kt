package uz.mahalla.feature.cinema.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.cinema.data.CinemaApi
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.feature.cinema.data.DefaultCinemaRepository
import javax.inject.Singleton

/**
 * Кино (issue #106). API — на **основном** Retrofit: покупка, свои билеты и
 * возврат требуют Bearer, а «голый» `@RefreshClient` его не ставит. Афиша и
 * расписание анонимны, но лишний заголовок им не мешает.
 */
@Module
@InstallIn(SingletonComponent::class)
object CinemaDataModule {

    @Provides
    @Singleton
    fun provideCinemaApi(retrofit: Retrofit): CinemaApi = retrofit.create(CinemaApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface CinemaBindingsModule {

    @Binds
    fun bindCinemaRepository(impl: DefaultCinemaRepository): CinemaRepository
}
