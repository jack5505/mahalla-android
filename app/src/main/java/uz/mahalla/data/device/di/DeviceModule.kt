package uz.mahalla.data.device.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.data.device.AndroidDeviceInfoProvider
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.AndroidLocationSource
import uz.mahalla.data.location.DefaultRequestLocationProvider
import uz.mahalla.data.location.LocationSource
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.push.FirebasePushTokenProvider
import uz.mahalla.data.push.PushTokenProvider

/**
 * Устройство и координаты для запросов авторизации (issue #42): бэкенд
 * требует их у `send-otp`, `verify-otp` и `refresh`.
 *
 * Сюда же токен пушей (эпик 11): он часть описания устройства — другого места
 * в контракте у него нет.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DeviceModule {

    @Binds
    fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    @Binds
    fun bindPushTokenProvider(impl: FirebasePushTokenProvider): PushTokenProvider

    @Binds
    fun bindLocationSource(impl: AndroidLocationSource): LocationSource

    @Binds
    fun bindRequestLocationProvider(
        impl: DefaultRequestLocationProvider,
    ): RequestLocationProvider
}
