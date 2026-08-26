package uz.mahalla.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Общие мелочи графа (эпик 1.1).
 *
 * Часы вынесены в зависимость намеренно: любой код, считающий «когда истекает
 * токен» или «сколько ждать в очереди», иначе становится непроверяемым.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
