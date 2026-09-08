package uz.mahalla.core.image.di

import android.content.Context
import coil.ImageLoader
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import uz.mahalla.core.image.BackendImageUrlInterceptor
import uz.mahalla.core.image.MahallaImageLoader
import javax.inject.Singleton

/** Загрузчик изображений в графе (issue #60). */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    /**
     * @param client основной клиент приложения — [Lazy], потому что
     * [ImageLoader] создаётся на старте (`ImageLoaderFactory`), а сетевой стек
     * к этому моменту поднимать незачем: он понадобится первому запросу, а не
     * первой картинке.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: Lazy<OkHttpClient>,
        backendImageUrlInterceptor: BackendImageUrlInterceptor,
    ): ImageLoader = MahallaImageLoader.create(
        context = context,
        callFactory = { MahallaImageLoader.imageClient(client.get()) },
        backendImageUrlInterceptor = backendImageUrlInterceptor,
    )
}
