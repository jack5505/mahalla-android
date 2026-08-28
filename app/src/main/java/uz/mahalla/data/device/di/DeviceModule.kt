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

/**
 * Устройство и координаты для запросов авторизации (issue #42): бэкенд
 * требует их у `send-otp`, `verify-otp` и `refresh`.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DeviceModule {

    @Binds
    fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    @Binds
    fun bindLocationSource(impl: AndroidLocationSource): LocationSource

    @Binds
    fun bindRequestLocationProvider(
        impl: DefaultRequestLocationProvider,
    ): RequestLocationProvider
}
