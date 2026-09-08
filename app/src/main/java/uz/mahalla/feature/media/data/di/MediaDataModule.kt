package uz.mahalla.feature.media.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.media.data.AndroidImageCompressor
import uz.mahalla.feature.media.data.DefaultMediaRepository
import uz.mahalla.feature.media.data.ImageCompressor
import uz.mahalla.feature.media.data.MediaApi
import uz.mahalla.feature.media.data.MediaRepository
import javax.inject.Singleton

/**
 * Загрузка файлов (issue #101). API — на **основном** Retrofit: `media/upload`
 * требует Bearer, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaDataModule {

    @Provides
    @Singleton
    fun provideMediaApi(retrofit: Retrofit): MediaApi = retrofit.create(MediaApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface MediaBindingsModule {

    @Binds
    fun bindMediaRepository(impl: DefaultMediaRepository): MediaRepository

    @Binds
    fun bindImageCompressor(impl: AndroidImageCompressor): ImageCompressor
}
