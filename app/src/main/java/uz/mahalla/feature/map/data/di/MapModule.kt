package uz.mahalla.feature.map.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.BuildConfig
import uz.mahalla.data.location.LocationSource
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.map.data.DeviceUserLocationProvider
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.MapKitKeyStore
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
     * Ключ карты: из сборки, а если её собрали без секрета — из того, что ввёл
     * пользователь (issue #129). Право вводить — то же, что у адреса бэкенда
     * (issue #26): в магазинной сборке конфигурацию меняет только релиз.
     */
    @Provides
    @Singleton
    fun provideMapKitKeyStore(settingsDataStore: SettingsDataStore): MapKitKeyStore =
        MapKitKeyStore(
            settingsDataStore = settingsDataStore,
            buildKey = BuildConfig.MAPKIT_API_KEY,
            canEdit = BuildConfig.BACKEND_URL_OVERRIDE,
        )

    /**
     * Локаль берётся из `Locale.getDefault()`, а не из настроек приложения:
     * `MapKitFactory.setLocale` вызывается один раз за процесс и до
     * инициализации, то есть раньше, чем DataStore успеет ответить. Смена языка
     * в приложении и так пересоздаёт процесс/активити (эпик 3.1).
     */
    @Provides
    @Singleton
    fun provideMapKitInitializer(
        sdk: MapKitSdk,
        keyStore: MapKitKeyStore,
    ): MapKitInitializer = MapKitInitializer(
        apiKey = keyStore::current,
        locale = mapKitLocale(Locale.getDefault().language),
        sdk = sdk,
    )

    /**
     * Два источника координат (issue #126): MapKit, пока он поднят, и системный
     * `LocationManager` запасным. Без ключа карты MapKit не отвечает вовсе, и
     * «моё местоположение» отказывало при выданном разрешении.
     */
    @Provides
    @Singleton
    fun provideUserLocationProvider(
        initializer: MapKitInitializer,
        locationSource: LocationSource,
    ): UserLocationProvider = DeviceUserLocationProvider(
        mapKit = MapKitLocationProvider(initializer),
        system = locationSource,
    )
}
