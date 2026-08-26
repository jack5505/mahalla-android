package uz.mahalla.feature.map.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.BuildConfig
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.MapKitLocationProvider
import uz.mahalla.feature.map.data.MapKitSdk
import uz.mahalla.feature.map.data.UserLocationProvider
import uz.mahalla.feature.map.data.YandexMapKitSdk
import uz.mahalla.feature.map.data.mapKitLocale
import java.util.Locale
import javax.inject.Singleton

/** Карта (эпик 4.2): Yandex MapKit и всё, что ему нужно на старте. */
@Module
@InstallIn(SingletonComponent::class)
object MapModule {

    @Provides
    @Singleton
    fun provideMapKitSdk(@ApplicationContext context: Context): MapKitSdk = YandexMapKitSdk(context)

    /**
     * Локаль берётся из `Locale.getDefault()`, а не из настроек приложения:
     * `MapKitFactory.setLocale` вызывается один раз за процесс и до
     * инициализации, то есть раньше, чем DataStore успеет ответить. Смена языка
     * в приложении и так пересоздаёт процесс/активити (эпик 3.1).
     */
    @Provides
    @Singleton
    fun provideMapKitInitializer(sdk: MapKitSdk): MapKitInitializer = MapKitInitializer(
        apiKey = BuildConfig.MAPKIT_API_KEY,
        locale = mapKitLocale(Locale.getDefault().language),
        sdk = sdk,
    )

    @Provides
    @Singleton
    fun provideUserLocationProvider(initializer: MapKitInitializer): UserLocationProvider =
        MapKitLocationProvider(initializer)
}
