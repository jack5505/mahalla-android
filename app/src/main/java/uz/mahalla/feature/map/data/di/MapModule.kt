package uz.mahalla.feature.map.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.BuildConfig
import uz.mahalla.data.location.LocationSource
import uz.mahalla.feature.map.data.DeviceUserLocationProvider
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
     * Локаль читается из `Locale.getDefault()` не здесь, а лениво — лямбдой,
     * которую [MapKitInitializer] вызовет сам при первом показе карты.
     * Причина: `MapKitFactory.setLocale` действует только до `initialize()` и
     * один раз за процесс, а в момент создания этого `@Singleton` DataStore
     * ещё не успел ответить про выбранный язык.
     *
     * Смена языка в приложении — `Activity.recreate()` (`ProfileScreen.kt`,
     * `WelcomeScreen.kt`), она НЕ пересоздаёт процесс: этот синглтон и
     * значение, которое вернула лямбда при первом вызове, переживают смену
     * языка. Если к моменту смены языка карта уже поднята, её подписи
     * останутся на старой локали до конца процесса — `setLocale` второй раз
     * MapKit не зовёт. Честный перезапуск процесса ради подписей карты —
     * отдельное продуктовое решение, не сделанное здесь.
     */
    @Provides
    @Singleton
    fun provideMapKitInitializer(sdk: MapKitSdk): MapKitInitializer = MapKitInitializer(
        apiKey = BuildConfig.MAPKIT_API_KEY,
        localeProvider = { mapKitLocale(Locale.getDefault().language) },
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
