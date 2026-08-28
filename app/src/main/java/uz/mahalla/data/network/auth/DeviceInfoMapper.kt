package uz.mahalla.data.network.auth

import uz.mahalla.data.device.DeviceDescriptor

/** Описание устройства в том виде, в каком его ждёт бэкенд (issue #42). */
fun DeviceDescriptor.toDto(): DeviceInfoDto = DeviceInfoDto(
    deviceId = deviceId,
    platform = platform,
    deviceName = deviceName,
    osVersion = osVersion,
    appVersion = appVersion,
    fcmToken = fcmToken,
)
